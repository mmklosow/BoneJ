package org.doube.util;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageStatistics;

/**
 * Check if an image conforms to the type defined by each method.
 * 
 * Testing git
 * 
 * @author Michael Doube
 * 
 */
public class ImageCheck {

	/**
	 * ImageJ version required by BoneJ
	 */
	public static final String requiredIJVersion = "1.43m";

	/**
	 * Check if image is binary
	 * 
	 * @param imp
	 * @return true if image is binary
	 */
	public boolean isBinary(ImagePlus imp) {
		if (imp == null) {
			IJ.noImage();
			return false;
		}
		if (imp.getType() != ImagePlus.GRAY8)
			return false;

		ImageStatistics stats = imp.getStatistics();
		if (stats.histogram[0] + stats.histogram[255] != stats.pixelCount)
			return false;
		return true;
	}

	/**
	 * Check if an image is a multi-slice image stack
	 * 
	 * @param imp
	 * @return true if the image has >= 2 slices
	 */
	public boolean isMultiSlice(ImagePlus imp) {
		if (imp == null) {
			IJ.noImage();
			return false;
		}

		if (imp.getStackSize() < 2)
			return false;
		return true;
	}

	/**
	 * Check if the image's voxels are isotropic in all 3 dimensions (i.e. are
	 * placed on a cubic grid)
	 * 
	 * @param imp
	 *            image to test
	 * @param tolerance
	 *            tolerated fractional deviation from equal length
	 * @return true if voxel width == height == depth
	 */
	public boolean isVoxelIsotropic(ImagePlus imp, double tolerance) {
		if (imp == null) {
			IJ.noImage();
			return false;
		}
		Calibration cal = imp.getCalibration();
		final double vW = cal.pixelWidth;
		final double vH = cal.pixelHeight;
		final double vD = cal.pixelDepth;
		final double tLow = 1 - tolerance;
		final double tHigh = 1 + tolerance;

		if (vW < vH * tLow || vW > vH * tHigh)
			return false;
		if (vW < vD * tLow || vW > vD * tHigh)
			return false;
		if (vH < vD * tLow || vH > vD * tHigh)
			return false;

		return true;
	}

	/**
	 * Run isVoxelIsotropic() with a default tolerance of 0%
	 * 
	 * @param imp
	 *            input image
	 * @return false if voxel dimensions are not equal
	 */
	public boolean isVoxelIsotropic(ImagePlus imp) {
		return isVoxelIsotropic(imp, 0);
	}

	/**
	 * Check that the voxel thickness is correct
	 * 
	 * @param imp
	 * @return voxel thickness based on DICOM header information. Returns -1 if
	 *         there is no DICOM slice position information.
	 */
	public double dicomVoxelDepth(ImagePlus imp) {
		double vD = imp.getCalibration().pixelDepth;

		String position = getDicomAttribute(imp, 1, "0020,0032");
		if (position == null) {
			IJ.log("No DICOM slice position data");
			return -1;
		}
		String[] xyz = position.split("\\\\");
		double first = 0;
		if (xyz.length == 3) // we have 3 values
			first = Double.parseDouble(xyz[2]);
		else
			return -1;

		position = getDicomAttribute(imp, imp.getStackSize(), "0020,0032");
		xyz = position.split("\\\\");
		double last = 0;
		if (xyz.length == 3) // we have 3 values
			last = Double.parseDouble(xyz[2]);
		else
			return -1;

		double sliceSpacing = Math.abs((last - first)
				/ (imp.getStackSize() - 1));

		String units = imp.getCalibration().getUnits();

		if (vD != sliceSpacing) {
			IJ
					.log("Voxel Depth Error: \n"
							+ "Voxel depth does not agree with slice spacing.\n"
							+ "Voxel depth: " + IJ.d2s(vD, 4) + " " + units
							+ "\n" + "Slice spacing: "
							+ IJ.d2s(sliceSpacing, 4) + " " + units);
		}
		return sliceSpacing;
	}

	/**
	 * Get the value associated with a DICOM tag from an ImagePlus header
	 * 
	 * @param imp
	 * @param slice
	 * @param tag
	 *            , in 0000,0000 format.
	 * @return the value associated with the tag
	 */
	private String getDicomAttribute(ImagePlus imp, int slice, String tag) {
		ImageStack stack = imp.getImageStack();
		String header = stack.getSliceLabel(slice);
		// tag must be in format 0000,0000
		if (slice < 1 || slice > stack.getSize()) {
			return null;
		}
		if (header == null) {
			return null;
		}
		String attribute = " ";
		String value = " ";
		int idx1 = header.indexOf(tag);
		int idx2 = header.indexOf(":", idx1);
		int idx3 = header.indexOf("\n", idx2);
		if (idx1 >= 0 && idx2 >= 0 && idx3 >= 0) {
			try {
				attribute = header.substring(idx1 + 9, idx2);
				attribute = attribute.trim();
				value = header.substring(idx2 + 1, idx3);
				value = value.trim();
				// IJ.log("tag = " + tag + ", attribute = " + attribute
				// + ", value = " + value);
			} catch (Throwable e) {
				return " ";
			}
		}
		return value;
	}

	/**
	 * Show a message and return false if the version of IJ is too old for BoneJ
	 * 
	 * @return false if the IJ version is too old
	 */
	public static boolean checkIJVersion() {
		if (requiredIJVersion.compareTo(IJ.getVersion()) > 0) {
			IJ.error("Update ImageJ",
					"You are using an old version of ImageJ, v"
							+ IJ.getVersion() + ".\n"
							+ "Please update to at least ImageJ v"
							+ requiredIJVersion + " using Help-Update ImageJ.");
			return false;
		} else
			return true;
	}

	/**
	 * Check that IJ has enough memory to do the job
	 * 
	 * @param memoryRequirement
	 *            Estimated required memory
	 * @return True if there is enough memory or if the user wants to continue.
	 *         False if the user wants to continue despite a risk of
	 *         insufficient memory
	 */
	public static boolean checkMemory(long memoryRequirement) {
		if (memoryRequirement > IJ.maxMemory()) {
			String message = "You might not have enough memory to run this job.\n"
					+ "Do you want to continue?";
			if (IJ.showMessageWithCancel("Memory Warning", message)) {
				return true;
			} else {
				return false;
			}
		} else {
			return true;
		}
	}
	
	public static boolean checkMemory(ImagePlus imp, double ratio){
		double size = ((double)imp.getWidth()*imp.getHeight()*imp.getStackSize());
    	switch (imp.getType()) {
	    	case ImagePlus.GRAY8:
	    	case ImagePlus.COLOR_256:
	    		break;
	    	case ImagePlus.GRAY16:
				size *= 2.0;
	    		break;
	    	case ImagePlus.GRAY32:
				size *= 4.0;
	    		break;
	    	case ImagePlus.COLOR_RGB:
				size *= 4.0;
	    		break;
    	}
		long memoryRequirement = (long) (size * ratio) ;
		return checkMemory(memoryRequirement);
	}
}
