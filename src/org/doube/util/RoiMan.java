package org.doube.util;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.frame.RoiManager;

import java.awt.List;
import java.awt.Rectangle;

/**
 * Do useful things with ImageJ's ROI Manager
 * 
 * @author mdoube
 * */
public class RoiMan {
	/**
	 * Get the calibrated 3D coordinates of point ROIs from the ROI manager
	 * 
	 * @param imp
	 * @param roiMan
	 * @return double[n][3] containing n (x, y, z) coordinates
	 */
	public static double[][] getRoiManPoints(ImagePlus imp, RoiManager roiMan) {
		Calibration cal = imp.getCalibration();
		double vW = cal.pixelWidth;
		double vH = cal.pixelHeight;
		double vD = cal.pixelDepth;
		int nPoints = 0;
		List listRoi = roiMan.getList();
		Roi[] roiList = roiMan.getRoisAsArray();
		for (int i = 0; i < roiMan.getCount(); i++) {
			Roi roi = roiList[i];
			if (roi.getType() == 10) {
				nPoints++;
			}
		}
		double[][] dataPoints = new double[nPoints][3];
		int j = 0;
		for (int i = 0; i < roiMan.getCount(); i++) {
			Roi roi = roiList[i];
			if (roi.getType() == 10) {
				String label = listRoi.getItem(i);
				Rectangle xy = roi.getBoundingRect();
				dataPoints[j][0] = xy.getX() * vW;
				dataPoints[j][1] = xy.getY() * vH;
				dataPoints[j][2] = roiMan.getSliceNumber(label) * vD;
				j++;
			}
		}
		return dataPoints;
	}
}
