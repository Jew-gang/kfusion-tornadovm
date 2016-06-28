package kfusion.pipeline;

import static tornado.collections.types.Float4.mult;
import static tornado.runtime.api.TaskUtils.createTask;
import kfusion.TornadoModel;
import kfusion.devices.Device;
import kfusion.tornado.algorithms.Integration;
import kfusion.tornado.algorithms.IterativeClosestPoint;
import kfusion.tornado.algorithms.Raycast;
import tornado.collections.graphics.GraphicsMath;
import tornado.collections.graphics.ImagingOps;
import tornado.collections.graphics.Renderer;
import tornado.collections.matrix.MatrixFloatOps;
import tornado.collections.matrix.MatrixMath;
import tornado.collections.types.Float4;
import tornado.collections.types.ImageFloat3;
import tornado.collections.types.Matrix4x4Float;
import tornado.runtime.api.CompilableTask;
import tornado.runtime.api.TaskGraph;
import tornado.drivers.opencl.runtime.OCLDeviceMapping;

public class TornadoOpenGLPipeline<T extends TornadoModel> extends AbstractOpenGLPipeline<T> {

	public TornadoOpenGLPipeline(T config) {
        super(config);
    }

    private OCLDeviceMapping deviceMapping;
	
	/**
	 * Tornado
	 */

	private TaskGraph preprocessingGraph;
	private TaskGraph estimatePoseGraph;
	private TaskGraph trackingPyramid[];
	private TaskGraph integrateGraph;
	private TaskGraph raycastGraph;
	private TaskGraph renderGraph;

	private Matrix4x4Float pyramidPose;
	private Matrix4x4Float[] scaledInvKs;
	

	@Override
	public void configure(final Device device) {
		super.configure(device);

		/**
		 * Tornado tasks
		 */
		deviceMapping = (OCLDeviceMapping) config.getTornadoDevice();
		System.err.printf(
			"mapping onto %s\n", deviceMapping.toString());
		
		/*
		 * Cleanup after previous configurations
		 */
		deviceMapping.getBackend().reset();

		pyramidPose = new Matrix4x4Float();
		pyramidDepths[0] = filteredDepthImage;
		pyramidVerticies[0] = currentView.getVerticies();
		pyramidNormals[0] = currentView.getNormals();

		final Matrix4x4Float scenePose = sceneView.getPose();

		//@formatter:off
		preprocessingGraph = new TaskGraph()
			.streamIn(depthImageInput)
			.add(ImagingOps::mm2metersKernel,scaledDepthImage, depthImageInput, scalingFactor)
			.add(ImagingOps::bilateralFilter,pyramidDepths[0], scaledDepthImage, gaussian, eDelta, radius)
//			.streamOut(scaledDepthImage,filteredDepthImage,pyramidDepths[0])
			.mapAllTo(deviceMapping);
		//@formatter:on

		final int iterations = pyramidIterations.length;
		scaledInvKs = new Matrix4x4Float[iterations];
		for (int i = 0; i < iterations; i++) {
			final Float4 cameraDup = mult(
				scaledCamera, 1f / (1 << i));
			scaledInvKs[i] = new Matrix4x4Float();
			GraphicsMath.getInverseCameraMatrix(cameraDup, scaledInvKs[i]);
		}

		estimatePoseGraph = new TaskGraph();

		for (int i = 1; i < iterations; i++) {
			//@formatter:off
			estimatePoseGraph
				.add(ImagingOps::resizeImage6,pyramidDepths[i], pyramidDepths[i - 1], 2, eDelta * 3, 2);
			//@formatter:on
		}

		for (int i = 0; i < iterations; i++) {
			//@formatter:off
			estimatePoseGraph
				.add(GraphicsMath::depth2vertex,pyramidVerticies[i], pyramidDepths[i], scaledInvKs[i])
				.add(GraphicsMath::vertex2normal,pyramidNormals[i], pyramidVerticies[i]);
			//@formatter:on
		}
		
		estimatePoseGraph
			.streamIn(projectReference)
			.mapAllTo(deviceMapping);

		trackingPyramid = new TaskGraph[iterations];
		for (int i = 0; i < iterations; i++) {

			final CompilableTask trackPose = createTask(IterativeClosestPoint::trackPose,
				pyramidTrackingResults[i], pyramidVerticies[i], pyramidNormals[i],
				referenceView.getVerticies(), referenceView.getNormals(), pyramidPose,
				projectReference, distanceThreshold, normalThreshold);

			//@formatter:off
			trackingPyramid[i] = new TaskGraph()
					.streamIn(pyramidPose)
					.add(trackPose)
					.streamOut(pyramidTrackingResults[i])
					.mapAllTo(deviceMapping);
			//@formatter:on
		}

		//@formatter:off
		integrateGraph = new TaskGraph()
			.streamIn(invTrack)
			.add(Integration::integrate,scaledDepthImage, invTrack, K, volumeDims, volume, mu, maxWeight)
			.mapAllTo(deviceMapping);
		//@formatter:on

		final ImageFloat3 verticies = referenceView.getVerticies();
		final ImageFloat3 normals = referenceView.getNormals();

		final CompilableTask raycast = createTask(Raycast::raycast,
			verticies, normals, volume, volumeDims, referencePose, nearPlane, farPlane, largeStep,
			smallStep);

		//@formatter:off
		raycastGraph = new TaskGraph()
			.streamIn(referencePose)
			.add(raycast)
			.mapAllTo(deviceMapping);
		//@formatter:on

		final CompilableTask renderVolume = createTask(Renderer::renderVolume,
			renderedScene, volume, volumeDims, scenePose, nearPlane, farPlane * 2f, smallStep,
			largeStep, light, ambient);

		//@formatter:off
		renderGraph = new TaskGraph()
			.streamIn(scenePose)
			.add(Renderer::renderLight,renderedCurrentViewImage, pyramidVerticies[0], pyramidNormals[0],light, ambient)
			.add(Renderer::renderLight,renderedReferenceViewImage, verticies, normals, light, ambient)
			.add(Renderer::renderTrack,renderedTrackingImage, pyramidTrackingResults[0])
//			.add(Renderer::renderDepth,renderedDepthImage, filteredDepthImage, nearPlane, farPlane)	
			.add(renderVolume)
			.streamOut(renderedCurrentViewImage, renderedReferenceViewImage, renderedTrackingImage, renderedDepthImage, renderedScene)
			.mapAllTo(deviceMapping);
		//@formatter:on

//		preprocessingGraph.printCompileTimes();
//		estimatePoseGraph.printCompileTimes();
//		for (final TaskGraph trackingLevel : trackingPyramid) {
//			trackingLevel.printCompileTimes();
//		}
//		integrateGraph.printCompileTimes();
//		raycastGraph.printCompileTimes();
//		renderGraph.printCompileTimes();

	}

	@Override
	protected boolean estimatePose() {
		// long t0 = System.nanoTime();
		if (config.debug()) {
			info("============== estimaing pose ==============");
		}

		

		invReferencePose.set(referenceView.getPose());
		MatrixFloatOps.inverse(invReferencePose);

		MatrixMath.sgemm(
			K, invReferencePose, projectReference);

		if (config.debug()) {
			info(
				"camera matrix    :\n%s", K.toString());
			info(
				"inv ref pose     :\n%s", invReferencePose.toString());
			info(
				"project reference:\n%s", projectReference.toString());
		}
		
		estimatePoseGraph.schedule().waitOn();
		
		// long t1 = System.nanoTime();

		// perform ICP
		pyramidPose.set(currentView.getPose());
		for (int level = pyramidIterations.length - 1; level >= 0; level--) {
			for (int i = 0; i < pyramidIterations[level]; i++) {
				trackingPyramid[level].schedule().waitOn();

				final boolean updated = IterativeClosestPoint.estimateNewPose(config,
					trackingResult, pyramidTrackingResults[level], pyramidPose, 1e-5f);

				pyramidPose.set(trackingResult.getPose());
				
				if (updated) {
					break;
				}

			}
		}

		// long t2 = System.nanoTime();

		// if the tracking result meets our constraints, update the current view with the estimated
		// pose
		final boolean hasTracked = trackingResult.getRSME() < RSMEThreshold
				&& trackingResult.getTracked(scaledInputSize.getX() * scaledInputSize.getY()) >= trackingThreshold;
		if (hasTracked) {
			currentView.getPose().set(
				trackingResult.getPose());
		}

		// long t3 = System.nanoTime();

		// System.out.printf("estimatePose: prepare=%.8f s, pyramid=%.8f s, post=%.8f s\n",
		// RuntimeUtilities.elapsedTimeInSeconds(t0, t1),
		// RuntimeUtilities.elapsedTimeInSeconds(t1, t2),
		// RuntimeUtilities.elapsedTimeInSeconds(t3, t3)
		// );

		return hasTracked;
	}

	@Override
	public void execute() {
		final boolean haveDepthImage = depthCamera.pollDepth(depthImageInput);
		final boolean haveVideoImage = videoCamera.pollVideo(videoImageInput);

		if (haveDepthImage) {
			preprocessing();

			final boolean hasTracked = estimatePose();

			if (hasTracked && frames % integrationRate == 0 || firstPass) {

				integrate();

				updateReferenceView();

				if (firstPass) {
					firstPass = false;
				}
			}

		}

		if (haveVideoImage) {

			ImagingOps.resizeImage(
				scaledVideoImage, videoImageInput, scalingFactor);

		}

		if (frames % renderingRate == 0) {
			final Matrix4x4Float scenePose = sceneView.getPose();
			final Matrix4x4Float tmp = new Matrix4x4Float();
			final Matrix4x4Float tmp2 = new Matrix4x4Float();

			if (config.getAndClearRotateNegativeX()) {
				updateRotation(
					rot, config.getUptrans());
			}

			if (config.getAndClearRotatePositiveX()) {
				updateRotation(
					rot, config.getDowntrans());
			}

			if (config.getAndClearRotatePositiveY()) {
				updateRotation(
					rot, config.getRighttrans());
			}

			if (config.getAndClearRotateNegativeY()) {
				updateRotation(
					rot, config.getLefttrans());
			}

			MatrixMath.sgemm(
				trans, rot, tmp);
			MatrixMath.sgemm(
				tmp, preTrans, tmp2);
			MatrixMath.sgemm(
				tmp2, invK, scenePose);

			renderGraph.schedule().waitOn();

		}

	}

	@Override
	protected void integrate() {
		invTrack.set(currentView.getPose());
		MatrixFloatOps.inverse(invTrack);

		integrateGraph.schedule().waitOn();

	}

	@Override
	protected void preprocessing() {
		preprocessingGraph.schedule().waitOn();
	}

	@Override
	public void updateReferenceView() {
		if (config.debug()) {
			info("============== updating reference view ==============");
		}

		referenceView.getPose().set(currentView.getPose());

		// convert the tracked pose into correct co-ordinate system for
		// raycasting
		// which system (homogeneous co-ordinates? or virtual image?)
		MatrixMath.sgemm(
			currentView.getPose(), scaledInvK, referencePose);

		raycastGraph.schedule().waitOn();
	}
}