/*
 *    This file is part of Slambench-Tornado: A Tornado version of the SLAMBENCH computer vision benchmark suite
 *    https://github.com/beehive-lab/slambench-tornado
 *
 *    Copyright (c) 2013-2017 APT Group, School of Computer Science,
 *    The University of Manchester
 *
 *    This work is partially supported by EPSRC grants:
 *    Anyscale EP/L000725/1 and PAMELA EP/K008730/1.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    Authors: James Clarkson
 */
package kfusion.ui;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JPanel;

import com.jogamp.opengl.util.Animator;

import kfusion.TornadoModel;
import kfusion.devices.Device;

public class TornadoModelConfigPanel extends JPanel implements ActionListener {

	private static final long serialVersionUID = 4887971237978617495L;

	final InputDeviceConfigPanel<TornadoModel> inputDeviceConfig;
	final VolumeConfigPanel<TornadoModel> volumeConfig;
	final IntrinsicCameraPanel<TornadoModel> cameraConfig;
	final TornadoConfigPanel tornadoConfig;

	public TornadoModelConfigPanel(final TornadoModel config, final Animator animator,
			TornadoConfigPanel tornadoPanel) {
		tornadoConfig = tornadoPanel;
		final JButton resetButton = new JButton("Reset");
		final JButton startButton = new JButton("Start");

		inputDeviceConfig = new InputDeviceConfigPanel<TornadoModel>(config, startButton, resetButton, this);

		volumeConfig = new VolumeConfigPanel<TornadoModel>(config);
		cameraConfig = new IntrinsicCameraPanel<TornadoModel>(config, inputDeviceConfig.getComboBox());

		resetButton.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				animator.stop();
				config.reset();

				// if(config.getDevice() == null){

				if (config.getDevice() != null) {
					config.getDevice().stop();
					config.getDevice().shutdown();
				}

				// final Device device = (Device)
				// inputDeviceConfig.getComboBox().getSelectedItem();
				config.setDevice(null);

				// }

				config.setReset();
			}

		});

		startButton.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				if (startButton.getText().equals("Stop")) {
					// if(animator.isStarted())
					// animator.stop();

					final Device currentDevice = config.getDevice();
					if (currentDevice.isRunning()) {
						currentDevice.stop();
						// currentDevice.shutdown();
					}

					// KfusionModel.config.setDevice(null);
					// KfusionModel.config.reset();

					startButton.setText("Start");
				} else {

					if (config.getDevice() == null) {
						final Device device = (Device) inputDeviceConfig.getComboBox().getSelectedItem();
						if (device.isRunning()) {
							device.stop();
							device.shutdown();
						}

						config.setDevice(device);

						device.init();
						device.updateModel(config);
						volumeConfig.updateModel();
						cameraConfig.updateModel();
						tornadoConfig.updateModel();
						config.setReset();
					}

					if (!animator.isStarted())
						animator.start();

					config.getDevice().start();

					startButton.setText("Stop");
				}
			}

		});

		add(inputDeviceConfig);
		add(tornadoConfig);
		add(cameraConfig);
		add(volumeConfig);
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		volumeConfig.resetConfig();
		cameraConfig.resetConfig();
	}

}
