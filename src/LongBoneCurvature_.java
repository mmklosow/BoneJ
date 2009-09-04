import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.plugin.filter.PlugInFilter;
import ij.plugin.frame.RoiManager;
import ij.measure.Calibration;
import ij.gui.*;

import java.awt.List;
import java.awt.Rectangle;
import java.awt.event.*;

import Jama.*;

import org.doube.bonej.Neck_Shaft_Angle;

public class LongBoneCurvature_ implements PlugInFilter {
    ImagePlus imp;
    ImageCanvas canvas;
    protected ImageStack stack;
    public float[] CTable;
    public double[] coeff = {0,1}, neckPoint = {78.75, 100.55, 80}, headCentre, centroid;
    public double[][] shaftVector;
    public int minT = 0, maxT = 4000; //min and maximum bone value in HU
    public int startSlice = 1, endSlice;
    public String title, units, valueUnit;
    public Calibration cal;
    public int setup(String arg, ImagePlus imp){
	if (imp == null || imp.getNSlices() < 2){
	    IJ.showMessage("A stack must be open");
	    return DONE;
	}
	this.imp = imp;
	this.stack = imp.getStack();
	this.endSlice = stack.getSize();
	this.cal = imp.getCalibration();
	this.coeff = cal.getCoefficients();
	this.title = imp.getShortTitle();
	return DOES_16 + STACK_REQUIRED;
    }
    
    public void run(ImageProcessor ip){
	return;
    }

}
