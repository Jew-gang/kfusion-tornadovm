package kfusion;

import java.nio.FloatBuffer;

import tornado.collections.types.Float4;
import tornado.collections.types.FloatOps;
import tornado.collections.types.ImageFloat;
import tornado.collections.types.Matrix4x4Float;
import tornado.collections.types.VectorFloat;

public class UtilsTest {

	public static void main(String[] args) {
		
		final String type = args[0];
		final String filename = args[1];
		
		try {
		switch(type){
			case "VectorFloat32":
				final VectorFloat v32 = new VectorFloat(32);
				Utils.loadData(filename, v32.asBuffer());
				System.out.println(v32.toString("%.4e"));
				break;
			case "Float4":
				final Float4 v4 = new Float4();
				
				Utils.loadData(filename, v4.asBuffer());
				System.out.println(v4.toString(FloatOps.fmt4em));
				break;
			
			case "Matrix4x4Float":
				final Matrix4x4Float m4x4 = new Matrix4x4Float();
				
				Utils.loadData(filename, m4x4.asBuffer());
				System.out.println(m4x4.toString(FloatOps.fmt4em));
				break;
				
			case "ImageFloat320x240":
				final ImageFloat img320_240 = new ImageFloat(320,240);
				
				Utils.loadData(filename, img320_240.asBuffer());
				System.out.println(img320_240.summerise());
				break;
				
			case "Float":
				FloatBuffer fb = FloatBuffer.allocate(1);
				Utils.loadData(filename, fb);
				fb.flip();
				System.out.printf("%.4e\n",fb.get());
				break;
			default:
				System.err.println("Unknown type: " + type);
		}
		
		} catch(Exception e){
			e.printStackTrace();
		}
		

	}

}