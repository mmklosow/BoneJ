/**
 *Neck Shaft Angle ImageJ plugin
 *Copyright 2008 2009 2010 Michael Doube 
 *
 *This program is free software: you can redistribute it and/or modify
 *it under the terms of the GNU General Public License as published by
 *the Free Software Foundation, either version 3 of the License, or
 *(at your option) any later version.
 *
 *This program is distributed in the hope that it will be useful,
 *but WITHOUT ANY WARRANTY; without even the implied warranty of
 *MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *GNU General Public License for more details.
 *
 *You should have received a copy of the GNU General Public License
 *along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ImageProcessor;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import ij.measure.Calibration;
import ij.gui.*;

import java.awt.Rectangle;
import java.awt.event.*;

import org.doube.bonej.Moments;
import org.doube.geometry.FitCircle;
import org.doube.geometry.FitSphere;
import org.doube.geometry.Trig;
import org.doube.jama.*;
import org.doube.util.ImageCheck;
import org.doube.util.ResultInserter;
import org.doube.util.RoiMan;

/*
 * TODO incorporate curvature
 * curvature = radius of circle fit to diaphyseal slice centroids / bone length
 */
/**
 *<p>
 * Neck Shaft Angle<br />
 * 
 * Tool to calculate the neck shaft angle of 3D images of femora.
 * </p>
 * <p>
 * Neck shaft angle is the angle formed at the intersection between the coplanar
 * lines <b>N</b> and <b>S</b>. <br>
 * <b>S</b> is the singular value decomposition (orthogonal distance regression)
 * vector that passes through the centroid (<b>B</b>) of the bone and describes
 * the long axis of the bone.<br/>
 * <b>C</b> is the centre of a sphere fit to the femoral head.<br/>
 * 
 * <b>P</b> is the plane that contains <b>S</b> and <b>C</b>. <br />
 * <b>N</b> is the projection onto <b>P</b> of a vector originating at <b>C</b>
 * and passing through the 'middle' of the femoral neck.<br />
 * 
 * Singular value decomposition performed with the <a
 * href="http://math.nist.gov/javanumerics/jama/">Jama</a> package
 * </p>
 * 
 *@author Michael Doube
 *@version 0.1
 */
public class Neck_Shaft_Angle implements PlugIn, MouseListener {

	private ImageCanvas canvas;

	private double[] headCentre;

	private double[][] shaftVector;

	private double[] centroid;

	public void run(String arg) {
		if (!ImageCheck.checkIJVersion())
			return;
		ImagePlus imp = IJ.getImage();
		if (null == imp) {
			IJ.noImage();
			return;
		}
		ImageCheck ic = new ImageCheck();
		if (!ic.isMultiSlice(imp)) {
			IJ.error("A stack must be open");
			return;
		}

		ImageProcessor ip = imp.getProcessor();
		double minT = ip.getAutoThreshold();
		double maxT = ip.getMax();
		Calibration cal = imp.getCalibration();
		String valueUnit = "";
		// set up pixel calibration
		if (!cal.isSigned16Bit() && !cal.calibrated()) {
			IJ.run("Threshold...");
			new WaitForUserDialog(
					"This image is not density calibrated.\nSet the threshold, then click OK.")
					.show();
			minT = ip.getMinThreshold();
			maxT = ip.getMaxThreshold();
			IJ.log("Image is uncalibrated: using user-determined threshold "
					+ minT + " to " + maxT);
		} else if (cal.getCoefficients()[0] == -1000
				&& cal.getCoefficients()[1] == 1.0) {
			// looks like an HU calibrated image
			minT = 0;
			maxT = 4000;
			valueUnit = "HU";
			IJ.log("Image looks like it is HU calibrated. Using " + minT
					+ " and " + maxT + " " + valueUnit + " as bone cutoffs");
		} else if (cal.isSigned16Bit() && !cal.calibrated()) {
			new WaitForUserDialog(
					"This image is not density calibrated.\nSet the threshold, then click OK.")
					.show();
			minT = ip.getMinThreshold();
			maxT = ip.getMaxThreshold();
			IJ.log("Image is uncalibrated: using user-determined threshold "
					+ minT + " to " + maxT);
		} else {
			IJ.error("Unrecognised file type");
			return;
		}
		GenericDialog gd = new GenericDialog("Setup");
		gd.addNumericField("Shaft Start Slice:", 1, 0);
		gd.addNumericField("Shaft End Slice:", imp.getImageStackSize(), 0);
		gd.addMessage("Only use pixels between clip values:");
		gd.addNumericField("Clip min.", minT, 0, 6, valueUnit);
		gd.addNumericField("Clip max.", maxT, 0, 6, valueUnit);
		gd.addCheckbox("Calculate curvature", true);
		gd.showDialog();
		if (gd.wasCanceled()) {
			return;
		}
		final int startSlice = (int) gd.getNextNumber();
		final int endSlice = (int) gd.getNextNumber();
		minT = gd.getNextNumber();
		maxT = gd.getNextNumber();
		final boolean doCurvature = gd.getNextBoolean();

		final double min = cal.getRawValue(minT);
		final double max = cal.getRawValue(maxT);

		// get coordinates from the ROI manager and fit a sphere
		RoiManager roiMan = RoiManager.getInstance();
		if (roiMan == null && imp != null) {
			IJ.run("ROI Manager...");
			IJ.error("Please populate ROI Manager with point ROIs\n"
					+ "placed on the boundary of the femoral head");
			return;
		} else {
			double[][] points = RoiMan.getRoiManPoints(imp, roiMan);
			try {
				this.headCentre = FitSphere.fitSphere(points);
			} catch (IllegalArgumentException ia) {
				IJ.showMessage(ia.getMessage());
				return;
			} catch (RuntimeException re) {
				IJ
						.showMessage("Can't fit sphere to points.\n"
								+ "Add more point ROI's to the ROI Manager and try again.");
				return;
			}
		}
		ImageWindow win = imp.getWindow();
		this.canvas = win.getCanvas();

		// work out the centroid and regression vector of the bone
		Moments m = new Moments();
		final double[] centroid = m.getCentroid3D(imp, startSlice, endSlice,
				minT, maxT, 0, 1);
		this.centroid = centroid;
		if (centroid[0] < 0) {
			IJ.error("Empty Stack", "No voxels available for calculation."
					+ "\nCheck your ROI and threshold.");
			return;
		}

		this.shaftVector = regression3D(imp, centroid, startSlice, endSlice,
				min, max);

		if (doCurvature)
			calculateCurvature(imp, this.shaftVector, this.headCentre,
					centroid, startSlice, endSlice, min, max);

		// remove stale MouseListeners
		MouseListener[] l = this.canvas.getMouseListeners();
		for (int n = 0; n < l.length; n++) {
			this.canvas.removeMouseListener(l[n]);
		}
		// add a new MouseListener
		this.canvas.addMouseListener(this);

		new WaitForUserDialog("Click on the middle of the femoral neck.\n"
				+ "Neck-shaft angle and out-of-plane skew\n"
				+ "will be recorded until you hit \'OK\'").show();
		this.canvas.removeMouseListener(this);
		return;
	}

	/**
	 * Calculate the vector associated with the projection plane from the
	 * regression vector and the vector connecting the centroid and the femoral
	 * head centre
	 * 
	 * @param shaftVector
	 *            double[][]
	 * @param headCentre
	 *            double[][]
	 * @param centroid
	 *            double[][]
	 * @return double[][] projectionPlane
	 */
	private double[][] getProjectionPlane(double[][] shaftVector,
			double[] headCentre, double[] centroid) {
		// have to calculate distance between points
		// so that we find a unit vector

		double d = Trig.distance3D(headCentre, centroid);
		double[][] cHVec = new double[3][1];
		cHVec[0][0] = (headCentre[0] - centroid[0]) / d;
		cHVec[1][0] = (headCentre[1] - centroid[1]) / d;
		cHVec[2][0] = (headCentre[2] - centroid[2]) / d;

		Matrix cH = new Matrix(cHVec);
		cH.printToIJLog("cHVec");

		// projectionPlane is the cross product of cHVec and shaftVector
		double[][] projectionPlane = crossProduct(cHVec, shaftVector);

		d = Trig.distance3D(projectionPlane[0][0], projectionPlane[1][0],
				projectionPlane[2][0]);
		projectionPlane[0][0] /= d;
		projectionPlane[1][0] /= d;
		projectionPlane[2][0] /= d;

		return projectionPlane;
	}

	/**
	 * Calculate the vector associated with the plane formed between neckVector
	 * and the normal to projectionPlane from the regression vector and the
	 * vector connecting the centroid and the femoral head centre
	 * 
	 * @param projectionPlane
	 *            double[][]
	 * @param neckVector
	 *            double[][]
	 * @return double[][] neckPlane
	 */
	private double[][] neckPlane(double[][] projectionPlane,
			double[][] neckVector) {
		// neckPlane is the cross product of neckVector and projectionPlane
		return crossProduct(projectionPlane, neckVector);
	}

	/**
	 * Find the intersection between neckPlane and projectionPlane
	 * 
	 * @param projectionPlane
	 * @param neckPlane
	 * @return double[][] testVector.
	 */
	public double[][] testVector(double[][] projectionPlane,
			double[][] neckPlane) {
		// testVector is the cross product of neckPlane and projectionPlane
		return crossProduct(projectionPlane, neckPlane);
	}

	/**
	 * Calculate the orthogonal distance regression plane of a set of points by
	 * the covariance method and Singular Value Decomposition
	 * 
	 * @param stack
	 * @param centroid
	 * @return SingularValueDecomposition containing eigenvector and eigenvalue
	 * 
	 * @see <a
	 *      href="http://mathforum.org/library/drmath/view/63765.html">Description
	 *      on Ask Dr Math</a>
	 * 
	 */
	public double[][] regression3D(ImagePlus imp, double[] centroid,
			int startSlice, int endSlice, double min, double max) {
		IJ.showStatus("Calculating SVD");
		ImageStack stack = imp.getImageStack();
		Rectangle r = stack.getRoi();
		final double cX = centroid[0];
		final double cY = centroid[1];
		final double cZ = centroid[2];
		Calibration cal = imp.getCalibration();
		final double vW = cal.pixelWidth;
		final double vH = cal.pixelHeight;
		final double vD = cal.pixelDepth;
		final int rX = r.x;
		final int rY = r.y;
		final int rW = r.x + r.width;
		final int rH = r.y + r.height;
		double sDxDx = 0;
		double sDyDy = 0;
		double sDzDz = 0;
		double sDxDy = 0;
		double sDxDz = 0;
		double sDyDz = 0;
		double count = 0;
		for (int z = startSlice; z <= endSlice; z++) {
			IJ.showStatus("Getting covariance matrix...");
			IJ.showProgress(z, endSlice);
			final double dz = z * vD - cZ;
			final double dzdz = dz * dz;
			final ImageProcessor ip = stack.getProcessor(z);
			for (int y = rY; y < rH; y++) {
				final double dy = y * vH - cY;
				final double dydy = dy * dy;
				final double dydz = dy * dz;
				for (int x = rX; x < rW; x++) {
					final double testPixel = ip.get(x, y);
					if (testPixel >= min && testPixel <= max) {
						final double dx = x * vW - cX;
						sDxDx += dx * dx;
						sDyDy += dydy;
						sDzDz += dzdz;
						sDxDy += dx * dy;
						sDxDz += dx * dz;
						sDyDz += dydz;
						count++;
					}
				}
			}
		}
		double[][] C = new double[3][3];
		C[0][0] = sDxDx;
		C[1][1] = sDyDy;
		C[2][2] = sDzDz;
		C[0][1] = sDxDy;
		C[0][2] = sDxDz;
		C[1][0] = sDxDy;
		C[1][2] = sDyDz;
		C[2][0] = sDxDz;
		C[2][1] = sDyDz;
		double invCount = 1 / count;
		Matrix covarianceMatrix = new Matrix(C).times(invCount);
		covarianceMatrix.printToIJLog("Covariance matrix");
		SingularValueDecomposition S = new SingularValueDecomposition(
				covarianceMatrix);
		Matrix leftVectors = S.getU();
		leftVectors.printToIJLog("Left vectors");
		double[][] orthogonalDistanceRegression = new double[3][1];
		orthogonalDistanceRegression[0][0] = leftVectors.get(0, 0);
		orthogonalDistanceRegression[1][0] = leftVectors.get(1, 0);
		orthogonalDistanceRegression[2][0] = leftVectors.get(2, 0);
		return orthogonalDistanceRegression;
	}/* end Regression3D */

	private double[][] neckVector(double[] headCentre, double[] neckPoint) {
		// have to calculate d to make sure that neckVector is a unit vector
		double d = Trig.distance3D(headCentre, neckPoint);

		double[][] neckVector = new double[3][1];
		neckVector[0][0] = (headCentre[0] - neckPoint[0]) / d;
		neckVector[1][0] = (headCentre[1] - neckPoint[1]) / d;
		neckVector[2][0] = (headCentre[2] - neckPoint[2]) / d;
		return neckVector;
	}

	private void calculateAngles(ImagePlus imp, double[] neckPoint) {
		double[][] neckVector = neckVector(headCentre, neckPoint);
		double[][] projectionPlane = getProjectionPlane(shaftVector,
				headCentre, this.centroid);
		double[][] neckPlane = neckPlane(neckVector, projectionPlane);
		double[][] testVector = testVector(projectionPlane, neckPlane);
		// P . Q = ||P|| ||Q|| cos(a) so if P and Q are unit vectors, then P.Q =
		// cos(a)
		Matrix PP = new Matrix(projectionPlane);
		PP.printToIJLog("projectionPlane");

		Matrix tV = new Matrix(testVector);
		tV.printToIJLog("testVector");

		Matrix sV = new Matrix(shaftVector);
		sV.printToIJLog("shaftVector");

		Matrix nV = new Matrix(neckVector);
		nV.printToIJLog("neckVector");

		double cosA1 = sV.get(0, 0) * tV.get(0, 0) + sV.get(1, 0)
				* tV.get(1, 0) + sV.get(2, 0) * tV.get(2, 0);
		// printMatrix(cosA1, "cosA1");
		IJ.log("cosA1: " + cosA1);

		double cosA2 = nV.get(0, 0) * tV.get(0, 0) + nV.get(1, 0)
				* tV.get(1, 0) + nV.get(2, 0) * tV.get(2, 0);
		// printMatrix(cosA2, "cosA2");
		IJ.log("cosA2: " + cosA2);

		double neckShaftAngle = Math.acos(cosA1);
		double neckShaftSkew = Math.acos(cosA2);
		ResultInserter ri = ResultInserter.getInstance();
		ri.setResultInRow(imp, "Angle (rad)", neckShaftAngle);
		ri.setResultInRow(imp, "Skew (rad)", neckShaftSkew);
		ri.updateTable();
		return;
	}

	/**
	 * <p>
	 * Calculate curvature of bone using shaft vector as a reference axis and
	 * centre of femoral head to define reference plane
	 * </p>
	 * 
	 * @param stack
	 * @param shaftVector
	 * @param headCentre
	 */
	private void calculateCurvature(ImagePlus imp, double[][] shaftVector,
			double[] headCentre, double[] centroid, int startSlice,
			int endSlice, double min, double max) {
		// calculate the eigenvector of the reference plane containing
		// the shaftVector and the headCentre

		// get the 2D centroids
		final ImageStack stack = imp.getImageStack();
		Calibration cal = imp.getCalibration();
		final double vW = cal.pixelWidth;
		final double vH = cal.pixelHeight;
		final double vD = cal.pixelDepth;
		Rectangle r = stack.getRoi();
		final int rW = r.x + r.width;
		final int rH = r.y + r.height;
		// pixel counters
		double cstack = 0;

		boolean[] emptySlices = new boolean[stack.getSize() + 1];
		double[] cortArea = new double[stack.getSize() + 1];
		double[][] sliceCentroids = new double[2][stack.getSize() + 1];

		final double pixelArea = vW * vH;
		for (int s = startSlice; s <= endSlice; s++) {
			double sumX = 0;
			double sumY = 0;
			double cslice = 0;
			final ImageProcessor ip = stack.getProcessor(s);
			for (int y = r.y; y < rH; y++) {
				for (int x = r.x; x < rW; x++) {
					final double pixel = ip.get(x, y);
					if (pixel >= min && pixel <= max) {
						cslice++;
						cortArea[s] += pixelArea;
						sumX += x * vW;
						sumY += y * vH;
					}
				}
			}
			if (cslice > 0) {
				sliceCentroids[0][s] = sumX / cslice;
				sliceCentroids[1][s] = sumY / cslice;
				cstack += cslice;
				emptySlices[s] = false;
			} else {
				emptySlices[s] = true;
			}
		}

		double[][] projPlane = getProjectionPlane(shaftVector, headCentre,
				centroid);
		double pPx = projPlane[0][0];
		double pPy = projPlane[1][0];
		double pPz = projPlane[2][0];

		final double x1x = centroid[0];
		final double x1y = centroid[1];
		final double x1z = centroid[2];
		final double x2x = x1x + shaftVector[0][0];
		final double x2y = x1y + shaftVector[1][0];
		final double x2z = x1z + shaftVector[2][0];

		// for each centroid, calculate the vector to the 3D regression line
		// using equation 10 from
		// http://mathworld.wolfram.com/Point-LineDistance3-Dimensional.html
		// with denominator = 1 because we use a unit vector for |(x2 - x1)|

		double[][] mL = new double[endSlice - startSlice + 1][2];
		double[][] cC = new double[endSlice - startSlice + 1][2];
		int i = 0;
		for (int s = startSlice; s <= endSlice; s++) {
			if (!emptySlices[s]) {
				final double x0x = sliceCentroids[0][s];
				final double x0y = sliceCentroids[1][s];
				final double x0z = s * vD;

				// distance is magnitude of cross product of (x0 - x1) and (x0 -
				// x2)
				double[] a = { x0x - x1x, x0y - x1y, x0z - x1z };
				double[] b = { x0x - x2x, x0y - x2y, x0z - x2z };

				double[] cp = crossProduct(a, b);

				double distance = Trig.distance3D(cp);
				// IJ.log("distance to regression line is "+ distance +
				// " "+this.units+" for slice "+s);

				// work out t (number of unit vectors from centroid along
				// regression)
				// as per equation 3
				double t = -1
						* Trig.distance3D(x1x - x0x, x1y - x0y, x1z - x0z, x2x
								- x1x, x2y - x1y, x2z - x1z);

				// So now the intersection point x3 of the perpendicular is
				// known
				// as centroid + t * unitVector
				// and the vector to the deflection as (x0 - x3)

				double x3x = x1x + t * shaftVector[0][0];
				double x3y = x1y + t * shaftVector[1][0];
				double x3z = x1z + t * shaftVector[2][0];

				double defVectX = x0x - x3x;
				double defVectY = x0y - x3y;
				double defVectZ = x0z - x3z;

				// project deflection vector onto projection plane vector by
				// taking the dot product
				// this is the craniocaudal deflection

				double cranioCaudal = (defVectX * pPx + defVectY * pPy + defVectZ
						* pPz);
				cC[i][0] = cranioCaudal;
				cC[i][1] = t;
				// IJ.log("Craniocaudal deflection at slice "+s+" is "+cranioCaudal);

				// mediolateral deflection is distance in projectionPlane, i.e.
				// deflection projected onto projectionPlane // double cross
				// product
				// B x (A x B), provided that B is a unit vector

				double aBx = defVectY * pPz - defVectZ * pPy;
				double aBy = defVectZ * pPx - defVectX * pPz;
				double aBz = defVectX * pPy - defVectY * pPx;

				double mLx = pPy * aBz - pPz * aBy;
				double mLy = pPz * aBx - pPx * aBz;
				double mLz = pPx * aBy - pPy * aBx;

				double medioLateral = Math.sqrt(mLx * mLx + mLy * mLy + mLz
						* mLz);

				// give the scalar a direction
				double sign = (mLx * mLy * mLz) / Math.abs(mLx * mLy * mLz);

				medioLateral *= sign;

				// IJ.log("Mediolateral deflection at slice "+s+" is "+medioLateral);
				IJ.log(s + "," + t + ", " + distance + ", " + medioLateral
						+ ", " + cranioCaudal);
				mL[i][0] = medioLateral;
				mL[i][1] = t;
				i++;
			} else {
				// IJ.log("No pixels to calculate centroid in slice "+s);
			}
		}
		// Calculate circle fitting for mL and cC deflections
		ResultInserter ri = ResultInserter.getInstance();
		String units = cal.getUnits();

		double[] mLabR = FitCircle.hyperStable(mL);
		double[] cCabR = FitCircle.hyperStable(cC);
		ri.setResultInRow(imp, "M-L radius (" + units + ")", mLabR[2]);
		ri.setResultInRow(imp, "M-L centre X (" + units + ")", mLabR[0]);
		ri.setResultInRow(imp, "M-L centre Y (" + units + ")", mLabR[1]);
		ri.setResultInRow(imp, "Cr-Ca radius (" + units + ")", cCabR[2]);
		ri.setResultInRow(imp, "Cr-Ca centre X (" + units + ")", cCabR[0]);
		ri.setResultInRow(imp, "Cr-Ca centre Y (" + units + ")", cCabR[1]);
		ri.updateTable();
		return;
	}

	/**
	 * Calculate the cross product of 2 column vectors, both in double[3][1]
	 * format
	 * 
	 * @param a
	 *            first vector
	 * @param b
	 *            second vector
	 * @return resulting vector in double[3][1] format
	 */
	private static double[][] crossProduct(double[][] a, double[][] b) {
		double[][] c = new double[3][1];
		c[0][0] = a[1][0] * b[2][0] - a[2][0] * b[1][0];
		c[1][0] = a[2][0] * b[0][0] - a[0][0] * b[2][0];
		c[2][0] = a[0][0] * b[1][0] - a[1][0] * b[0][0];
		return c;
	}

	/**
	 * Calculate the cross product of 2 vectors, both in double[3] format
	 * 
	 * @param a
	 *            first vector
	 * @param b
	 *            second vector
	 * @return resulting vector in double[3] format
	 */
	private static double[] crossProduct(double[] a, double[] b) {
		double[] c = new double[3];
		c[0] = a[1] * b[2] - a[2] * b[1];
		c[1] = a[2] * b[0] - a[0] * b[2];
		c[2] = a[0] * b[1] - a[1] * b[0];
		return c;
	}

	public void mousePressed(MouseEvent e) {
		ImagePlus imp = IJ.getImage();
		Calibration cal = imp.getCalibration();
		int x = canvas.offScreenX(e.getX());
		int y = canvas.offScreenY(e.getY());
		int z = imp.getCurrentSlice();
		final double[] neckPoint = { x * cal.pixelWidth, y * cal.pixelHeight,
				z * cal.pixelDepth };
		IJ.log("neckPoint: (" + neckPoint[0] + "," + neckPoint[1] + ", "
				+ neckPoint[2] + ")");
		calculateAngles(imp, neckPoint);
	}

	public void mouseReleased(MouseEvent e) {
	}

	public void mouseExited(MouseEvent e) {
	}

	public void mouseClicked(MouseEvent e) {
	}

	public void mouseEntered(MouseEvent e) {
	}

	public void mouseMoved(MouseEvent e) {
	}

}
