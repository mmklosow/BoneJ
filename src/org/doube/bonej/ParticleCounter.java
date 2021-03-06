package org.doube.bonej;

/**
 * ParticleCounter Copyright 2009 2010 Michael Doube
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import javax.vecmath.Color3f;
import javax.vecmath.Point3f;

import org.doube.geometry.FitEllipsoid;
import org.doube.jama.EigenvalueDecomposition;
import org.doube.jama.Matrix;
import org.doube.util.ImageCheck;

import customnode.CustomPointMesh;
import customnode.CustomTriangleMesh;

import marchingcubes.MCTriangulator;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;
import ij3d.Content;
import ij3d.Image3DUniverse;

/**
 * <p>
 * This class implements multithreaded 3D particle identification and shape
 * analysis. Surface meshing and 3D visualisation are provided by Bene Schmid's
 * ImageJ 3D Viewer.
 * </p>
 * <p>
 * This plugin is based on Object_Counter3D by Fabrice P Cordelires and Jonathan
 * Jackson, but with significant speed increases through reduction of recursion
 * and multi-threading. Thanks to Robert Barbour for the suggestion to 'chunk'
 * the stack. Chunking works as follows:
 * </p>
 * <ol>
 * <li>Perform initial labelling on the whole stack in a single thread</li>
 * <li>for <i>n</i> discrete, contiguous chunks within the labelling array,
 * connectStructures()
 * <ol type="a">
 * <li>connectStructures() can run in a separate thread for each chunk</li>
 * <li>chunks are approximately equal-sized sets of slices</li>
 * </ol>
 * <li>stitchChunks() for the pixels on the first slice of each chunk, except
 * for the first chunk, restricting replaceLabels() to the current and all
 * previous chunks.
 * <ol type="a">
 * <li>stitchChunks() iterates through the slice being stitched in a single
 * thread</li>
 * </ol>
 * </li>
 * 
 * </ol>
 * <p>
 * The performance improvement should be in the region of a factor of <i>n</i>
 * if run linearly, and if multithreaded over <i>c</i> processors, speed
 * increase should be in the region of <i>n</i> * <i>c</i>, minus overhead.
 * </p>
 * 
 * @author Michael Doube, Jonathan Jackson, Fabrice Cordelires
 * @see <p>
 *      <a href="http://rsbweb.nih.gov/ij/plugins/track/objects.html">3D Object
 *      Counter</a>
 *      </p>
 * 
 */
public class ParticleCounter implements PlugIn {

	/** 3D viewer for rendering graphical output */
	private Image3DUniverse univ = new Image3DUniverse();

	/** Foreground value */
	public final static int FORE = -1;

	/** Background value */
	public final static int BACK = 0;

	private String sPhase = "";

	private String chunkString = "";

	public void run(String arg) {
		ImagePlus imp = IJ.getImage();
		if (null == imp) {
			IJ.noImage();
			return;
		}
		ImageCheck ic = new ImageCheck();
		if (!ic.isBinary(imp)) {
			IJ.error("Binary image required");
			return;
		}
		Calibration cal = imp.getCalibration();
		String units = cal.getUnits();
		GenericDialog gd = new GenericDialog("Setup");
		gd.addMessage("Measurement Options");
		gd.addNumericField("Min Volume", 0, 3, 7, units + "³");
		gd.addNumericField("Max Volume", Double.POSITIVE_INFINITY, 3, 7, units
				+ "³");
		gd.addCheckbox("Surface_area", true);
		gd.addCheckbox("Feret diameter", true);
		gd.addCheckbox("Enclosed_volume", true);
		gd.addNumericField("Surface_resampling", 2, 0);
		gd.addCheckbox("Moments of inertia", true);
		gd.addCheckbox("Euler characteristic", true);
		gd.addCheckbox("Thickness", true);
		gd.addCheckbox("Ellipsoids", true);
		gd.addMessage("Graphical Results");
		gd.addCheckbox("Show_particle stack", true);
		gd.addCheckbox("Show_size stack", false);
		gd.addCheckbox("Show_thickness stack", false);
		gd.addCheckbox("Show_surfaces (3D)", true);
		gd.addCheckbox("Show_centroids (3D)", true);
		gd.addCheckbox("Show_axes (3D)", true);
		gd.addCheckbox("Show_ellipsoids", true);
		gd.addCheckbox("Show_stack (3D)", true);
		gd.addNumericField("Volume_resampling", 2, 0);
		gd.addMessage("Slice size for particle counting");
		gd.addNumericField("Slices per chunk", 2, 0);
		gd.showDialog();
		if (gd.wasCanceled()) {
			return;
		}
		final double minVol = gd.getNextNumber();
		final double maxVol = gd.getNextNumber();
		final boolean doSurfaceArea = gd.getNextBoolean();
		final boolean doFeret = gd.getNextBoolean();
		final boolean doSurfaceVolume = gd.getNextBoolean();
		final int resampling = (int) Math.floor(gd.getNextNumber());
		final boolean doMoments = gd.getNextBoolean();
		final boolean doEulerCharacters = gd.getNextBoolean();
		final boolean doThickness = gd.getNextBoolean();
		final boolean doEllipsoids = gd.getNextBoolean();
		final boolean doParticleImage = gd.getNextBoolean();
		final boolean doParticleSizeImage = gd.getNextBoolean();
		final boolean doThickImage = gd.getNextBoolean();
		final boolean doSurfaceImage = gd.getNextBoolean();
		final boolean doCentroidImage = gd.getNextBoolean();
		final boolean doAxesImage = gd.getNextBoolean();
		final boolean doEllipsoidImage = gd.getNextBoolean();
		final boolean do3DOriginal = gd.getNextBoolean();
		final int origResampling = (int) Math.floor(gd.getNextNumber());
		final int slicesPerChunk = (int) Math.floor(gd.getNextNumber());

		// get the particles and do the analysis
		Object[] result = getParticles(imp, slicesPerChunk, minVol, maxVol,
				FORE);
		int[][] particleLabels = (int[][]) result[1];
		long[] particleSizes = getParticleSizes(particleLabels);
		final int nParticles = particleSizes.length;
		double[] volumes = getVolumes(imp, particleSizes);
		double[][] centroids = getCentroids(imp, particleLabels, particleSizes);
		int[][] limits = getParticleLimits(imp, particleLabels, nParticles);

		// set up resources for analysis
		ArrayList<List<Point3f>> surfacePoints = new ArrayList<List<Point3f>>();
		if (doSurfaceArea || doSurfaceVolume || doSurfaceImage || doEllipsoids
				|| doFeret) {
			surfacePoints = getSurfacePoints(imp, particleLabels, limits,
					resampling, nParticles);
		}
		EigenvalueDecomposition[] eigens = new EigenvalueDecomposition[nParticles];
		if (doMoments || doAxesImage) {
			eigens = getEigens(imp, particleLabels, centroids);
		}
		// calculate dimensions
		double[] surfaceAreas = new double[nParticles];
		if (doSurfaceArea) {
			surfaceAreas = getSurfaceArea(surfacePoints);
		}
		double[] ferets = new double[nParticles];
		if (doFeret) {
			ferets = getFerets(surfacePoints);
		}
		double[] surfaceVolumes = new double[nParticles];
		if (doSurfaceVolume) {
			surfaceVolumes = getSurfaceVolume(surfacePoints);
		}
		double[][] eulerCharacters = new double[nParticles][3];
		if (doEulerCharacters) {
			eulerCharacters = getEulerCharacter(imp, particleLabels, limits,
					nParticles);
		}
		double[][] thick = new double[nParticles][2];
		if (doThickness) {
			Thickness th = new Thickness();
			ImagePlus thickImp = th.getLocalThickness(imp, false);
			thick = getMeanStdDev(thickImp, particleLabels, particleSizes, 0);
			if (doThickImage) {
				double max = 0;
				for (int i = 1; i < nParticles; i++) {
					max = Math.max(max, thick[i][2]);
				}
				thickImp.getProcessor().setMinAndMax(0, max);
				thickImp.setTitle(imp.getShortTitle() + "_thickness");
				thickImp.show();
				IJ.run("Fire");
			}
		}
		Object[][] ellipsoids = new Object[nParticles][10];
		if (doEllipsoids || doEllipsoidImage) {
			ellipsoids = getEllipsoids(surfacePoints);
		}

		// Show numerical results
		ResultsTable rt = new ResultsTable();
		for (int i = 1; i < volumes.length; i++) {
			if (volumes[i] > 0) {
				rt.incrementCounter();
				rt.addLabel(imp.getTitle());
				rt.addValue("ID", i);
				rt.addValue("Vol. (" + units + "³)", volumes[i]);
				rt.addValue("x Cent (" + units + ")", centroids[i][0]);
				rt.addValue("y Cent (" + units + ")", centroids[i][1]);
				rt.addValue("z Cent (" + units + ")", centroids[i][2]);
				if (doSurfaceArea) {
					rt.addValue("SA (" + units + "²)", surfaceAreas[i]);
				}
				if (doFeret) {
					rt.addValue("Feret (" + units + ")", ferets[i]);
				}
				if (doSurfaceVolume) {
					rt.addValue("Encl. Vol. (" + units + "³)",
							surfaceVolumes[i]);
				}
				if (doMoments) {
					EigenvalueDecomposition E = eigens[i];
					rt.addValue("I1", E.getD().get(2, 2));
					rt.addValue("I2", E.getD().get(1, 1));
					rt.addValue("I3", E.getD().get(0, 0));
					rt.addValue("vX", E.getV().get(0, 2));
					rt.addValue("vY", E.getV().get(1, 2));
					rt.addValue("vZ", E.getV().get(2, 2));
				}
				if (doEulerCharacters) {
					rt.addValue("Euler (χ)", eulerCharacters[i][0]);
					rt.addValue("Holes (β1)", eulerCharacters[i][1]);
					rt.addValue("Cavities (β2)", eulerCharacters[i][2]);
				}
				if (doThickness) {
					rt.addValue("Thickness (" + units + ")", thick[i][0]);
					rt.addValue("SD Thickness (" + units + ")", thick[i][1]);
					rt.addValue("Max Thickness (" + units + ")", thick[i][2]);
				}
				if (doEllipsoids) {
					double[] rad = new double[3];
					if (ellipsoids[i] == null) {
						double[] r = { Double.NaN, Double.NaN, Double.NaN };
						rad = r;
					} else {
						Object[] el = ellipsoids[i];
						double[] radii = (double[]) el[1];
						rad = radii.clone();
						Arrays.sort(rad);
					}
					rt.addValue("Major radius (" + units + ")", rad[2]);
					rt.addValue("Int. radius (" + units + ")", rad[1]);
					rt.addValue("Minor radius (" + units + ")", rad[0]);
				}
				rt.updateResults();
			}
		}
		rt.show("Results");

		// Show resulting image stacks
		if (doParticleImage) {
			displayParticleLabels(particleLabels, imp).show();
			IJ.run("Fire");
		}
		if (doParticleSizeImage) {
			displayParticleValues(imp, particleLabels, volumes, "volume")
					.show();
			IJ.run("Fire");
		}

		// show 3D renderings
		if (doSurfaceImage || doCentroidImage || doAxesImage || do3DOriginal
				|| doEllipsoidImage) {
			univ.show();
			if (doSurfaceImage) {
				displayParticleSurfaces(surfacePoints);
			}
			if (doCentroidImage) {
				displayCentroids(centroids);
			}
			if (doAxesImage) {
				double[][] lengths = (double[][]) getMaxDistances(imp,
						particleLabels, centroids, eigens)[1];
				displayPrincipalAxes(eigens, centroids, lengths);
			}
			if (doEllipsoidImage) {
				displayEllipsoids(ellipsoids);
			}
			if (do3DOriginal) {
				display3DOriginal(imp, origResampling);
			}
			try {
				if (univ.contains(imp.getTitle())) {
					Content c = univ.getContent(imp.getTitle());
					univ.adjustView(c);
				}
			} catch (NullPointerException npe) {
				IJ.log("3D Viewer was closed before rendering completed.");
			}
		}
		IJ.showProgress(1.0);
		IJ.showStatus("Particle Analysis Complete");
		return;
	}

	private void displayEllipsoids(Object[][] ellipsoids) {
		final int nEllipsoids = ellipsoids.length;
		ellipsoidLoop: for (int el = 1; el < nEllipsoids; el++) {
			IJ.showStatus("Rendering ellipsoids...");
			IJ.showProgress(el, nEllipsoids);
			if (ellipsoids[el] == null)
				continue ellipsoidLoop;
			final double[] centre = (double[]) ellipsoids[el][0];
			final double[] radii = (double[]) ellipsoids[el][1];
			final double[][] eV = (double[][]) ellipsoids[el][2];
			for (int r = 0; r < 3; r++) {
				Double s = radii[r];
				if (s.equals(Double.NaN))
					continue ellipsoidLoop;
			}
			final double a = radii[0];
			final double b = radii[1];
			final double c = radii[2];
			double[][] ellipsoid = FitEllipsoid.testEllipsoid(a, b, c, 0, 0, 0,
					0, 0, 1000, false);
			final int nPoints = ellipsoid.length;
			// rotate points by eigenvector matrix
			// and add transformation for centre
			for (int p = 0; p < nPoints; p++) {
				final double x = ellipsoid[p][0];
				final double y = ellipsoid[p][1];
				final double z = ellipsoid[p][2];
				ellipsoid[p][0] = x * eV[0][0] + y * eV[0][1] + z * eV[0][2]
						+ centre[0];
				ellipsoid[p][1] = x * eV[1][0] + y * eV[1][1] + z * eV[1][2]
						+ centre[1];
				ellipsoid[p][2] = x * eV[2][0] + y * eV[2][1] + z * eV[2][2]
						+ centre[2];
			}

			List<Point3f> points = new ArrayList<Point3f>();
			for (int p = 0; p < nPoints; p++) {
				Point3f e = new Point3f();
				e.x = (float) ellipsoid[p][0];
				e.y = (float) ellipsoid[p][1];
				e.z = (float) ellipsoid[p][2];
				points.add(e);
			}
			CustomPointMesh mesh = new CustomPointMesh(points);
			mesh.setPointSize(1.0f);
			float red = 0.0f;
			float green = 0.5f;
			float blue = 1.0f;
			Color3f cColour = new Color3f(red, green, blue);
			mesh.setColor(cColour);
			try {
				univ.addCustomMesh(mesh, "Ellipsoid " + el).setLocked(true);
			} catch (NullPointerException npe) {
				IJ.log("3D Viewer was closed before rendering completed.");
				return;
			}
			// Add some axes
			displayAxes(centre, eV, radii, 1.0f, 1.0f, 0.0f, "Ellipsoid Axes "
					+ el);
		}
	}

	private Object[][] getEllipsoids(ArrayList<List<Point3f>> surfacePoints) {
		Object[][] ellipsoids = new Object[surfacePoints.size()][];
		int p = 0;
		Iterator<List<Point3f>> partIter = surfacePoints.iterator();
		while (partIter.hasNext()) {
			List<Point3f> points = partIter.next();
			if (points == null) {
				p++;
				continue;
			}
			Iterator<Point3f> pointIter = points.iterator();
			double[][] coOrdinates = new double[points.size()][3];
			int i = 0;
			while (pointIter.hasNext()) {
				Point3f point = pointIter.next();
				coOrdinates[i][0] = point.x;
				coOrdinates[i][1] = point.y;
				coOrdinates[i][2] = point.z;
				i++;
			}
			try {
				ellipsoids[p] = FitEllipsoid.yuryPetrov(coOrdinates);
			} catch (RuntimeException re) {
				IJ.log("Could not fit ellipsoid to surface " + p);
				ellipsoids[p] = null;
			}
			p++;
		}
		return ellipsoids;
	}

	/**
	 * Get the mean and standard deviation of pixel values above a minimum value
	 * for each particle in a particle label work array
	 * 
	 * @param imp
	 *            Input image containing pixel values
	 * @param particleLabels
	 *            workArray containing particle labels
	 * @param particleSizes
	 *            array of particle sizes as pixel counts
	 * @param threshold
	 *            restrict calculation to values > i
	 * @return array containing mean, std dev and max pixel values for each
	 *         particle
	 */
	private double[][] getMeanStdDev(ImagePlus imp, int[][] particleLabels,
			long[] particleSizes, final int threshold) {
		final int nParticles = particleSizes.length;
		final int d = imp.getImageStackSize();
		final int wh = imp.getWidth() * imp.getHeight();
		ImageStack stack = imp.getImageStack();
		double[] sums = new double[nParticles];
		for (int z = 0; z < d; z++) {
			float[] pixels = (float[]) stack.getPixels(z + 1);
			int[] labelPixels = particleLabels[z];
			for (int i = 0; i < wh; i++) {
				final double value = pixels[i];
				if (value > threshold) {
					sums[labelPixels[i]] += value;
				}
			}
		}
		double[][] meanStdDev = new double[nParticles][3];
		for (int p = 1; p < nParticles; p++) {
			meanStdDev[p][0] = sums[p] / particleSizes[p];
		}

		double[] sumSquares = new double[nParticles];
		for (int z = 0; z < d; z++) {
			float[] pixels = (float[]) stack.getPixels(z + 1);
			int[] labelPixels = particleLabels[z];
			for (int i = 0; i < wh; i++) {
				final double value = pixels[i];
				if (value > threshold) {
					final int p = labelPixels[i];
					final double residual = value - meanStdDev[p][0];
					sumSquares[p] += residual * residual;
					meanStdDev[p][2] = Math.max(meanStdDev[p][2], value);
				}
			}
		}
		for (int p = 1; p < nParticles; p++) {
			meanStdDev[p][1] = Math.sqrt(sumSquares[p] / particleSizes[p]);
		}
		return meanStdDev;
	}

	/**
	 * Get the Euler characteristic of each particle
	 * 
	 * @param imp
	 * @param particleLabels
	 * @param limits
	 * @param nParticles
	 * @return
	 */
	private double[][] getEulerCharacter(ImagePlus imp, int[][] particleLabels,
			int[][] limits, int nParticles) {
		Connectivity con = new Connectivity();
		double[][] eulerCharacters = new double[nParticles][3];
		for (int p = 1; p < nParticles; p++) {
			ImagePlus particleImp = getBinaryParticle(p, imp, particleLabels,
					limits, 1);
			double euler = con.getSumEuler(particleImp);
			double cavities = getNCavities(particleImp);
			// Calculate number of holes and cavities using
			// Euler = particles - holes + cavities
			// where particles = 1
			double holes = cavities - euler + 1;
			double[] bettis = { euler, holes, cavities };
			eulerCharacters[p] = bettis;
		}
		return eulerCharacters;
	}

	private int getNCavities(ImagePlus imp) {
		Object[] result = getParticles(imp, 4, BACK);
		long[] particleSizes = (long[]) result[2];
		final int nParticles = particleSizes.length;
		final int nCavities = nParticles - 2; // 1 particle is the background
		return nCavities;
	}

	/**
	 * Get the minimum and maximum x, y and z coordinates of each particle
	 * 
	 * @param imp
	 *            ImagePlus (used for stack size)
	 * @param particleLabels
	 *            work array containing labelled particles
	 * @param nParticles
	 *            number of particles in the stack
	 * @return int[][] containing x, y and z minima and maxima.
	 */
	private int[][] getParticleLimits(ImagePlus imp, int[][] particleLabels,
			int nParticles) {
		final int w = imp.getWidth();
		final int h = imp.getHeight();
		final int d = imp.getImageStackSize();
		int[][] limits = new int[nParticles][6];
		for (int i = 0; i < nParticles; i++) {
			limits[i][0] = Integer.MAX_VALUE; // x min
			limits[i][1] = 0; // x max
			limits[i][2] = Integer.MAX_VALUE; // y min
			limits[i][3] = 0; // y max
			limits[i][4] = Integer.MAX_VALUE; // z min
			limits[i][5] = 0; // z max
		}
		for (int z = 0; z < d; z++) {
			for (int y = 0; y < h; y++) {
				final int index = y * w;
				for (int x = 0; x < w; x++) {
					final int i = particleLabels[z][index + x];
					limits[i][0] = Math.min(limits[i][0], x);
					limits[i][1] = Math.max(limits[i][1], x);
					limits[i][2] = Math.min(limits[i][2], y);
					limits[i][3] = Math.max(limits[i][3], y);
					limits[i][4] = Math.min(limits[i][4], z);
					limits[i][5] = Math.max(limits[i][5], z);
				}
			}
		}
		return limits;
	}

	private EigenvalueDecomposition[] getEigens(ImagePlus imp,
			int[][] particleLabels, double[][] centroids) {
		Calibration cal = imp.getCalibration();
		final double vW = cal.pixelWidth;
		final double vH = cal.pixelHeight;
		final double vD = cal.pixelDepth;
		final double voxVhVd = (vH * vH + vD * vD) / 12;
		final double voxVwVd = (vW * vW + vD * vD) / 12;
		final double voxVhVw = (vH * vH + vW * vW) / 12;
		final int w = imp.getWidth();
		final int h = imp.getHeight();
		final int d = imp.getImageStackSize();
		final int nParticles = centroids.length;
		EigenvalueDecomposition[] eigens = new EigenvalueDecomposition[nParticles];
		double[][] momentTensors = new double[nParticles][6];
		for (int z = 0; z < d; z++) {
			IJ.showStatus("Calculating particle moments...");
			IJ.showProgress(z, d);
			final double zVd = z * vD;
			for (int y = 0; y < h; y++) {
				final double yVh = y * vH;
				final int index = y * w;
				for (int x = 0; x < w; x++) {
					final int p = particleLabels[z][index + x];
					if (p > 0) {
						final double xVw = x * vW;
						final double dx = xVw - centroids[p][0];
						final double dy = yVh - centroids[p][1];
						final double dz = zVd - centroids[p][2];
						momentTensors[p][0] += dy * dy + dz * dz + voxVhVd; // Ixx
						momentTensors[p][1] += dx * dx + dz * dz + voxVwVd; // Iyy
						momentTensors[p][2] += dy * dy + dx * dx + voxVhVw; // Izz
						momentTensors[p][3] += dx * dy; // Ixy
						momentTensors[p][4] += dx * dz; // Ixz
						momentTensors[p][5] += dy * dz; // Iyz
					}
				}
			}
			for (int p = 1; p < nParticles; p++) {
				double[][] inertiaTensor = new double[3][3];
				inertiaTensor[0][0] = momentTensors[p][0];
				inertiaTensor[1][1] = momentTensors[p][1];
				inertiaTensor[2][2] = momentTensors[p][2];
				inertiaTensor[0][1] = -momentTensors[p][3];
				inertiaTensor[0][2] = -momentTensors[p][4];
				inertiaTensor[1][0] = -momentTensors[p][3];
				inertiaTensor[1][2] = -momentTensors[p][5];
				inertiaTensor[2][0] = -momentTensors[p][4];
				inertiaTensor[2][1] = -momentTensors[p][5];
				Matrix inertiaTensorMatrix = new Matrix(inertiaTensor);
				EigenvalueDecomposition E = new EigenvalueDecomposition(
						inertiaTensorMatrix);
				eigens[p] = E;
			}
		}
		return eigens;
	}

	/**
	 * Get the maximum distances from the centroid in x, y, and z axes, and
	 * transformed x, y and z axes
	 * 
	 * @param imp
	 * @param particleLabels
	 * @param centroids
	 * @param E
	 * @return array containing two nPoints * 3 arrays with max and max
	 *         transformed distances respectively
	 * 
	 */
	private Object[] getMaxDistances(ImagePlus imp, int[][] particleLabels,
			double[][] centroids, EigenvalueDecomposition[] E) {
		Calibration cal = imp.getCalibration();
		final double vW = cal.pixelWidth;
		final double vH = cal.pixelHeight;
		final double vD = cal.pixelDepth;
		final int w = imp.getWidth();
		final int h = imp.getHeight();
		final int d = imp.getImageStackSize();
		final int nParticles = centroids.length;
		double[][] maxD = new double[nParticles][3];
		double[][] maxDt = new double[nParticles][3];
		for (int z = 0; z < d; z++) {
			for (int y = 0; y < h; y++) {
				final int index = y * w;
				for (int x = 0; x < w; x++) {
					final int p = particleLabels[z][index + x];
					if (p > 0) {
						final double dX = x * vW - centroids[p][0];
						final double dY = y * vH - centroids[p][1];
						final double dZ = z * vD - centroids[p][2];
						maxD[p][0] = Math.max(maxD[p][0], Math.abs(dX));
						maxD[p][1] = Math.max(maxD[p][1], Math.abs(dY));
						maxD[p][2] = Math.max(maxD[p][2], Math.abs(dZ));
						final double[][] eV = E[p].getV().getArray();
						final double dXt = dX * eV[0][0] + dY * eV[0][1] + dZ
								* eV[0][2];
						final double dYt = dX * eV[1][0] + dY * eV[1][1] + dZ
								* eV[1][2];
						final double dZt = dX * eV[2][0] + dY * eV[2][1] + dZ
								* eV[2][2];
						maxDt[p][0] = Math.max(maxDt[p][0], Math.abs(dXt));
						maxDt[p][1] = Math.max(maxDt[p][1], Math.abs(dYt));
						maxDt[p][2] = Math.max(maxDt[p][2], Math.abs(dZt));
					}
				}
			}
		}
		for (int p = 0; p < nParticles; p++) {
			Arrays.sort(maxDt[p]);
			double[] temp = new double[3];
			for (int i = 0; i < 3; i++) {
				temp[i] = maxDt[p][2 - i];
			}
			maxDt[p] = temp.clone();
		}
		final Object[] maxDistances = { maxD, maxDt };
		return maxDistances;
	}

	private void display3DOriginal(ImagePlus imp, int resampling) {
		Color3f colour = new Color3f(1.0f, 1.0f, 1.0f);
		boolean[] channels = { true, true, true };
		try {
			univ
					.addVoltex(imp, colour, imp.getTitle(), 0, channels,
							resampling).setLocked(true);
		} catch (NullPointerException npe) {
			IJ.log("3D Viewer was closed before rendering completed.");
		}
		return;
	}

	private void displayPrincipalAxes(EigenvalueDecomposition[] eigens,
			double[][] centroids, double[][] lengths) {
		final int nEigens = eigens.length;
		for (int p = 1; p < nEigens; p++) {
			IJ.showStatus("Rendering principal axes...");
			IJ.showProgress(p, nEigens);
			final Matrix eVec = eigens[p].getV();
			displayAxes(centroids[p], eVec.getArray(), lengths[p], 1.0f, 0.0f,
					0.0f, "Principal Axes " + p);
		}
		return;
	}

	/**
	 * Draws 3 orthogonal axes defined by the centroid, unitvector and axis
	 * length.
	 * 
	 * @param centroid
	 * @param unitVector
	 * @param lengths
	 * @param red
	 * @param green
	 * @param blue
	 * @param title
	 */
	private void displayAxes(double[] centroid, double[][] unitVector,
			double[] lengths, float red, float green, float blue, String title) {
		final double cX = centroid[0];
		final double cY = centroid[1];
		final double cZ = centroid[2];
		final double eVec1x = unitVector[0][0];
		final double eVec1y = unitVector[1][0];
		final double eVec1z = unitVector[2][0];
		final double eVec2x = unitVector[0][1];
		final double eVec2y = unitVector[1][1];
		final double eVec2z = unitVector[2][1];
		final double eVec3x = unitVector[0][2];
		final double eVec3y = unitVector[1][2];
		final double eVec3z = unitVector[2][2];
		final double l1 = lengths[0];
		final double l2 = lengths[1];
		final double l3 = lengths[2];

		List<Point3f> mesh = new ArrayList<Point3f>();
		Point3f start1 = new Point3f();
		start1.x = (float) (cX - eVec1x * l1);
		start1.y = (float) (cY - eVec1y * l1);
		start1.z = (float) (cZ - eVec1z * l1);
		mesh.add(start1);

		Point3f end1 = new Point3f();
		end1.x = (float) (cX + eVec1x * l1);
		end1.y = (float) (cY + eVec1y * l1);
		end1.z = (float) (cZ + eVec1z * l1);
		mesh.add(end1);

		Point3f start2 = new Point3f();
		start2.x = (float) (cX - eVec2x * l2);
		start2.y = (float) (cY - eVec2y * l2);
		start2.z = (float) (cZ - eVec2z * l2);
		mesh.add(start2);

		Point3f end2 = new Point3f();
		end2.x = (float) (cX + eVec2x * l2);
		end2.y = (float) (cY + eVec2y * l2);
		end2.z = (float) (cZ + eVec2z * l2);
		mesh.add(end2);

		Point3f start3 = new Point3f();
		start3.x = (float) (cX - eVec3x * l3);
		start3.y = (float) (cY - eVec3y * l3);
		start3.z = (float) (cZ - eVec3z * l3);
		mesh.add(start3);

		Point3f end3 = new Point3f();
		end3.x = (float) (cX + eVec3x * l3);
		end3.y = (float) (cY + eVec3y * l3);
		end3.z = (float) (cZ + eVec3z * l3);
		mesh.add(end3);

		Color3f aColour = new Color3f(red, green, blue);
		try {
			univ.addLineMesh(mesh, aColour, title, false).setLocked(true);
		} catch (NullPointerException npe) {
			IJ.log("3D Viewer was closed before rendering completed.");
			return;
		}
	}

	/**
	 * Draw the particle centroids in a 3D viewer
	 * 
	 * @param centroids
	 */
	private void displayCentroids(double[][] centroids) {
		int nCentroids = centroids.length;
		for (int p = 1; p < nCentroids; p++) {
			IJ.showStatus("Rendering centroids...");
			IJ.showProgress(p, nCentroids);
			Point3f centroid = new Point3f();
			centroid.x = (float) centroids[p][0];
			centroid.y = (float) centroids[p][1];
			centroid.z = (float) centroids[p][2];
			List<Point3f> point = new ArrayList<Point3f>();
			point.add(centroid);
			CustomPointMesh mesh = new CustomPointMesh(point);
			mesh.setPointSize(5.0f);
			float red = 0.0f;
			float green = 0.5f * (float) p / (float) nCentroids;
			float blue = 1.0f;
			Color3f cColour = new Color3f(red, green, blue);
			mesh.setColor(cColour);
			try {
				univ.addCustomMesh(mesh, "Centroid " + p).setLocked(true);
			} catch (NullPointerException npe) {
				IJ.log("3D Viewer was closed before rendering completed.");
				return;
			}
		}
		return;
	}

	/**
	 * Draw the particle surfaces in a 3D viewer
	 * 
	 * @param surfacePoints
	 */
	private void displayParticleSurfaces(ArrayList<List<Point3f>> surfacePoints) {
		int p = 0;
		int drawnParticles = 0;
		final int nParticles = surfacePoints.size();
		Iterator<List<Point3f>> i = surfacePoints.iterator();
		while (i.hasNext()) {
			List<Point3f> points = i.next();
			if (p > 0 && points.size() > 0)
				drawnParticles++;
		}
		Iterator<List<Point3f>> iter = surfacePoints.iterator();
		while (iter.hasNext()) {
			IJ.showStatus("Rendering surfaces...");
			IJ.showProgress(p, nParticles);
			List<Point3f> points = iter.next();
			if (p > 0 && points.size() > 0) {
				float red = 1.0f - (float) p / (float) nParticles;
				float green = 1.0f - red;
				float blue = (float) p / (2.0f * (float) nParticles);
				Color3f pColour = new Color3f(red, green, blue);
				// Add the mesh
				try {
					univ.addTriangleMesh(points, pColour, "Surface " + p)
							.setLocked(true);
				} catch (NullPointerException npe) {
					IJ.log("3D Viewer was closed before rendering completed.");
					return;
				}
			}
			p++;
		}
	}

	private double[] getSurfaceArea(ArrayList<List<Point3f>> surfacePoints) {
		Iterator<List<Point3f>> iter = surfacePoints.iterator();
		double[] surfaceAreas = new double[surfacePoints.size()];
		int p = 0;
		while (iter.hasNext()) {
			List<Point3f> points = iter.next();
			if (null != points) {
				double surfaceArea = MeasureSurface.getSurfaceArea(points);
				surfaceAreas[p] = surfaceArea;
			}
			p++;
		}
		return surfaceAreas;
	}

	private double[] getSurfaceVolume(ArrayList<List<Point3f>> surfacePoints) {
		Iterator<List<Point3f>> iter = surfacePoints.iterator();
		double[] surfaceVolumes = new double[surfacePoints.size()];
		final Color3f colour = new Color3f(0.0f, 0.0f, 0.0f);
		int p = 0;
		while (iter.hasNext()) {
			IJ.showStatus("Calculating enclosed volume...");
			List<Point3f> points = iter.next();
			if (null != points) {
				CustomTriangleMesh surface = new CustomTriangleMesh(points,
						colour, 0.0f);
				surfaceVolumes[p] = surface.getVolume();
			}
			p++;
		}
		return surfaceVolumes;
	}

	@SuppressWarnings("unchecked")
	private ArrayList<List<Point3f>> getSurfacePoints(ImagePlus imp,
			int[][] particleLabels, int[][] limits, int resampling,
			int nParticles) {
		Calibration cal = imp.getCalibration();
		ArrayList<List<Point3f>> surfacePoints = new ArrayList<List<Point3f>>();
		final boolean[] channels = { true, false, false };
		for (int p = 0; p < nParticles; p++) {
			IJ.showStatus("Getting surface meshes...");
			IJ.showProgress(p, nParticles);
			if (p > 0) {
				ImagePlus binaryImp = getBinaryParticle(p, imp, particleLabels,
						limits, resampling);
				MCTriangulator mct = new MCTriangulator();
				List<Point3f> points = mct.getTriangles(binaryImp, 128,
						channels, resampling);
				final double xOffset = (limits[p][0] - 1) * cal.pixelWidth;
				final double yOffset = (limits[p][2] - 1) * cal.pixelHeight;
				final double zOffset = (limits[p][4] - 1) * cal.pixelDepth;
				Iterator<Point3f> iter = points.iterator();
				while (iter.hasNext()) {
					Point3f point = iter.next();
					point.x += xOffset;
					point.y += yOffset;
					point.z += zOffset;
				}
				surfacePoints.add(points);
				if (points.size() == 0) {
					IJ.log("Particle " + p + " resulted in 0 surface points");
				}
			} else {
				surfacePoints.add(null);
			}
		}
		return surfacePoints;
	}

	/**
	 * Get the Feret diameter of a surface. Uses an inefficient brute-force
	 * algorithm.
	 * 
	 * @param particleSurfaces
	 * @return
	 */
	private double[] getFerets(ArrayList<List<Point3f>> particleSurfaces) {
		int nParticles = particleSurfaces.size();
		double[] ferets = new double[nParticles];
		ListIterator<List<Point3f>> it = particleSurfaces.listIterator();
		int i = 0;
		Point3f a;
		Point3f b;
		List<Point3f> surface;
		ListIterator<Point3f> ita;
		ListIterator<Point3f> itb;
		while (it.hasNext()) {
			IJ.showStatus("Finding Feret diameter...");
			IJ.showProgress(it.nextIndex(), nParticles);
			surface = it.next();
			if (surface == null) {
				ferets[i] = Double.NaN;
				i++;
				continue;
			}
			ita = surface.listIterator();
			while (ita.hasNext()) {
				a = ita.next();
				itb = surface.listIterator(ita.nextIndex());
				while (itb.hasNext()) {
					b = itb.next();
					ferets[i] = Math.max(ferets[i], a.distance(b));
				}
			}
			i++;
		}
		return ferets;
	}

	/**
	 * create a binary ImagePlus containing a single particle and which 'just
	 * fits' the particle
	 * 
	 * @param p
	 *            The particle ID to get
	 * @param imp
	 *            original image, used for calibration
	 * @param particleLabels
	 *            work array of particle labels
	 * @param limits
	 *            x,y and z limits of each particle
	 * @param padding
	 *            amount of empty space to pad around each particle
	 * @return
	 */
	private static ImagePlus getBinaryParticle(int p, ImagePlus imp,
			int[][] particleLabels, int[][] limits, int padding) {

		final int w = imp.getWidth();
		final int h = imp.getHeight();
		final int d = imp.getImageStackSize();
		final int xMin = Math.max(0, limits[p][0] - padding);
		final int xMax = Math.min(w - 1, limits[p][1] + padding);
		final int yMin = Math.max(0, limits[p][2] - padding);
		final int yMax = Math.min(h - 1, limits[p][3] + padding);
		final int zMin = Math.max(0, limits[p][4] - padding);
		final int zMax = Math.min(d - 1, limits[p][5] + padding);
		final int stackWidth = xMax - xMin + 1;
		final int stackHeight = yMax - yMin + 1;
		final int stackSize = stackWidth * stackHeight;
		ImageStack stack = new ImageStack(stackWidth, stackHeight);
		for (int z = zMin; z <= zMax; z++) {
			byte[] slice = new byte[stackSize];
			int i = 0;
			for (int y = yMin; y <= yMax; y++) {
				final int sourceIndex = y * w;
				for (int x = xMin; x <= xMax; x++) {
					if (particleLabels[z][sourceIndex + x] == p) {
						slice[i] = (byte) (255 & 0xFF);
					}
					i++;
				}
			}
			stack.addSlice(imp.getStack().getSliceLabel(z + 1), slice);
		}
		ImagePlus binaryImp = new ImagePlus("Particle_" + p, stack);
		Calibration cal = imp.getCalibration();
		binaryImp.setCalibration(cal);
		return binaryImp;
	}

	/**
	 * Create an image showing some particle measurement
	 * 
	 * @param imp
	 * @param particleLabels
	 * @param values
	 *            list of values whose array indices correspond to
	 *            particlelabels
	 * @param title
	 *            tag stating what we are displaying
	 * @return ImagePlus with particle labels substituted with some value
	 */
	private ImagePlus displayParticleValues(ImagePlus imp,
			int[][] particleLabels, double[] values, String title) {
		final int w = imp.getWidth();
		final int h = imp.getHeight();
		final int d = imp.getImageStackSize();
		final int wh = w * h;
		float[][] pL = new float[d][wh];
		values[0] = 0; // don't colour the background
		ImageStack stack = new ImageStack(w, h);
		for (int z = 0; z < d; z++) {
			for (int i = 0; i < wh; i++) {
				final int p = particleLabels[z][i];
				pL[z][i] = (float) values[p];
			}
			stack.addSlice(imp.getImageStack().getSliceLabel(z + 1), pL[z]);
		}
		final int nValues = values.length;
		double max = 0;
		for (int i = 0; i < nValues; i++) {
			max = Math.max(max, values[i]);
		}
		ImagePlus impOut = new ImagePlus(imp.getShortTitle() + "_" + title,
				stack);
		impOut.setCalibration(imp.getCalibration());
		impOut.getProcessor().setMinAndMax(0, max);
		return impOut;
	}

	/**
	 * Get the centroids of all the particles in real units
	 * 
	 * @param imp
	 * @param particleLabels
	 * @param particleSizes
	 * @return double[][] containing all the particles' centroids
	 */
	private double[][] getCentroids(ImagePlus imp, int[][] particleLabels,
			long[] particleSizes) {
		final int nParticles = particleSizes.length;
		final int w = imp.getWidth();
		final int h = imp.getHeight();
		final int d = imp.getImageStackSize();
		double[][] sums = new double[nParticles][3];
		for (int z = 0; z < d; z++) {
			for (int y = 0; y < h; y++) {
				final int index = y * w;
				for (int x = 0; x < w; x++) {
					final int particle = particleLabels[z][index + x];
					sums[particle][0] += x;
					sums[particle][1] += y;
					sums[particle][2] += z;
				}
			}
		}
		Calibration cal = imp.getCalibration();
		double[][] centroids = new double[nParticles][3];
		for (int p = 0; p < nParticles; p++) {
			centroids[p][0] = cal.pixelWidth * sums[p][0] / particleSizes[p];
			centroids[p][1] = cal.pixelHeight * sums[p][1] / particleSizes[p];
			centroids[p][2] = cal.pixelDepth * sums[p][2] / particleSizes[p];
		}
		return centroids;
	}

	private double[] getVolumes(ImagePlus imp, long[] particleSizes) {
		Calibration cal = imp.getCalibration();
		final double voxelVolume = cal.pixelWidth * cal.pixelHeight
				* cal.pixelDepth;
		final int nLabels = particleSizes.length;
		double[] particleVolumes = new double[nLabels];
		for (int i = 0; i < nLabels; i++) {
			particleVolumes[i] = voxelVolume * particleSizes[i];
		}
		return particleVolumes;
	}

	/**
	 * Get particles, particle labels and particle sizes from a 3D ImagePlus
	 * 
	 * @param imp
	 *            Binary input image
	 * @param slicesPerChunk
	 *            number of slices per chunk. 2 is generally good.
	 * @param minVol
	 *            minimum volume particle to include
	 * @param maxVol
	 *            maximum volume particle to include
	 * @param phase
	 *            foreground or background (FORE or BACK)
	 * @return Object[] {byte[][], int[][]} containing a binary workArray and
	 *         particle labels.
	 */
	public Object[] getParticles(ImagePlus imp, int slicesPerChunk,
			double minVol, double maxVol, int phase) {
		byte[][] workArray = makeWorkArray(imp);
		return getParticles(imp, workArray, slicesPerChunk, minVol, maxVol,
				phase);
	}

	public Object[] getParticles(ImagePlus imp, int slicesPerChunk, int phase) {
		byte[][] workArray = makeWorkArray(imp);
		double minVol = 0;
		double maxVol = Double.POSITIVE_INFINITY;
		return getParticles(imp, workArray, slicesPerChunk, minVol, maxVol,
				phase);
	}

	public Object[] getParticles(ImagePlus imp, byte[][] workArray,
			int slicesPerChunk, int phase) {
		double minVol = 0;
		double maxVol = Double.POSITIVE_INFINITY;
		return getParticles(imp, workArray, slicesPerChunk, minVol, maxVol,
				phase);
	}

	/**
	 * Get particles, particle labels and sizes from a workArray using an
	 * ImagePlus for scale information
	 * 
	 * @param imp
	 *            input binary image
	 * @param binary
	 *            work array
	 * @param slicesPerChunk
	 *            number of slices to use for each chunk
	 * @param minVol
	 *            minimum volume particle to include
	 * @param maxVol
	 *            maximum volume particle to include
	 * @param phase
	 *            FORE or BACK for foreground or background respectively
	 * @return Object[] array containing a binary workArray, particle labels and
	 *         particle sizes
	 */
	public Object[] getParticles(ImagePlus imp, byte[][] workArray,
			int slicesPerChunk, double minVol, double maxVol, int phase) {
		if (phase == FORE) {
			this.sPhase = "foreground";
		} else if (phase == BACK) {
			this.sPhase = "background";
		} else {
			throw new IllegalArgumentException();
		}
		if (slicesPerChunk < 1) {
			throw new IllegalArgumentException();
		}
		// Set up the chunks
		final int nChunks = getNChunks(imp, slicesPerChunk);
		final int[][] chunkRanges = getChunkRanges(imp, nChunks, slicesPerChunk);
		final int[][] stitchRanges = getStitchRanges(imp, nChunks,
				slicesPerChunk);

		int[][] particleLabels = firstIDAttribution(imp, workArray, phase);

		// connect particles within chunks
		final int nThreads = Runtime.getRuntime().availableProcessors();
		ConnectStructuresThread[] cptf = new ConnectStructuresThread[nThreads];
		for (int thread = 0; thread < nThreads; thread++) {
			cptf[thread] = new ConnectStructuresThread(thread, nThreads, imp,
					workArray, particleLabels, phase, nChunks, chunkRanges);
			cptf[thread].start();
		}
		try {
			for (int thread = 0; thread < nThreads; thread++) {
				cptf[thread].join();
			}
		} catch (InterruptedException ie) {
			IJ.error("A thread was interrupted.");
		}

		// connect particles between chunks
		if (nChunks > 1) {
			chunkString = ": stitching...";
			connectStructures(imp, workArray, particleLabels, phase,
					stitchRanges);
		}
		filterParticles(imp, workArray, particleLabels, minVol, maxVol, phase);
		minimiseLabels(particleLabels);
		long[] particleSizes = getParticleSizes(particleLabels);
		Object[] result = { workArray, particleLabels, particleSizes };
		return result;
	}

	/**
	 * Remove particles outside user-specified volume thresholds
	 * 
	 * @param imp
	 *            ImagePlus, used for calibration
	 * @param workArray
	 *            binary foreground and background information
	 * @param particleLabels
	 *            Packed 3D array of particle labels
	 * @param minVol
	 *            minimum (inclusive) particle volume
	 * @param maxVol
	 *            maximum (inclusive) particle volume
	 * @param phase
	 *            phase we are interested in
	 */
	private void filterParticles(ImagePlus imp, byte[][] workArray,
			int[][] particleLabels, double minVol, double maxVol, int phase) {
		if (minVol == 0 && maxVol == Double.POSITIVE_INFINITY)
			return;
		final int d = imp.getImageStackSize();
		final int wh = workArray[0].length;
		long[] particleSizes = getParticleSizes(particleLabels);
		double[] particleVolumes = getVolumes(imp, particleSizes);
		byte flip = 0;
		if (phase == FORE) {
			flip = (byte) 0;
		} else {
			flip = (byte) 255;
		}
		for (int z = 0; z < d; z++) {
			for (int i = 0; i < wh; i++) {
				final int p = particleLabels[z][i];
				final double v = particleVolumes[p];
				if (v < minVol || v > maxVol) {
					workArray[z][i] = flip;
					particleLabels[z][i] = 0;
				}
			}
		}
	}

	/**
	 * Gets rid of redundant particle labels
	 * 
	 * @param particleLabels
	 * @return
	 */
	private void minimiseLabels(int[][] particleLabels) {
		IJ.showStatus("Minimising labels...");
		final int d = particleLabels.length;
		long[] particleSizes = getParticleSizes(particleLabels);
		final int nLabels = particleSizes.length;
		int[] newLabel = new int[nLabels];
		int minLabel = 0;
		// find the minimised labels
		for (int i = 0; i < nLabels; i++) {
			if (particleSizes[i] > 0) {
				if (i == minLabel) {
					newLabel[i] = i;
					minLabel++;
					continue;
				} else {
					newLabel[i] = minLabel;
					particleSizes[minLabel] = particleSizes[i];
					particleSizes[i] = 0;
					minLabel++;
				}
			}
		}
		// now replace labels
		final int wh = particleLabels[0].length;
		for (int z = 0; z < d; z++) {
			IJ.showProgress(z, d);
			for (int i = 0; i < wh; i++) {
				final int p = particleLabels[z][i];
				if (p > 0) {
					particleLabels[z][i] = newLabel[p];
				}
			}
		}
		return;
	}

	/**
	 * Gets number of chunks needed to divide a stack into evenly-sized sets of
	 * slices.
	 * 
	 * @param imp
	 *            input image
	 * @param slicesPerChunk
	 *            number of slices per chunk
	 * @return number of chunks
	 */
	public int getNChunks(ImagePlus imp, int slicesPerChunk) {
		final int d = imp.getImageStackSize();
		int nChunks = (int) Math.floor((double) d / (double) slicesPerChunk);

		int remainder = d % slicesPerChunk;

		if (remainder > 0) {
			nChunks++;
		}
		return nChunks;
	}

	/**
	 * Go through all pixels and assign initial particle label
	 * 
	 * @param workArray
	 *            byte[] array containing pixel values
	 * @param phase
	 *            FORE or BACK for foreground of background respectively
	 * @return particleLabels int[] array containing label associating every
	 *         pixel with a particle
	 */
	private int[][] firstIDAttribution(ImagePlus imp, final byte[][] workArray,
			final int phase) {
		final int w = imp.getWidth();
		final int h = imp.getHeight();
		final int d = imp.getImageStackSize();
		final int wh = w * h;
		IJ.showStatus("Finding " + sPhase + " structures");
		int[][] particleLabels = new int[d][wh];
		int ID = 1;

		if (phase == FORE) {
			for (int z = 0; z < d; z++) {
				for (int y = 0; y < h; y++) {
					final int rowIndex = y * w;
					for (int x = 0; x < w; x++) {
						final int arrayIndex = rowIndex + x;
						if (workArray[z][arrayIndex] == phase) {
							particleLabels[z][arrayIndex] = ID;
							int minTag = ID;
							// Find the minimum particleLabel in the
							// neighbouring pixels
							for (int vZ = z - 1; vZ <= z + 1; vZ++) {
								for (int vY = y - 1; vY <= y + 1; vY++) {
									for (int vX = x - 1; vX <= x + 1; vX++) {
										if (withinBounds(vX, vY, vZ, w, h, 0, d)) {
											final int offset = getOffset(vX,
													vY, w);
											if (workArray[vZ][offset] == phase) {
												final int tagv = particleLabels[vZ][offset];
												if (tagv != 0 && tagv < minTag) {
													minTag = tagv;
												}
											}
										}
									}
								}
							}
							// assign the smallest particle label from the
							// neighbours to the pixel
							particleLabels[z][arrayIndex] = minTag;
							// increment the particle label
							if (minTag == ID) {
								ID++;
							}
						}
					}
				}
				IJ.showProgress(z, d);
			}
			ID++;
		} else if (phase == BACK) {
			for (int z = 0; z < d; z++) {
				for (int y = 0; y < h; y++) {
					final int rowIndex = y * w;
					for (int x = 0; x < w; x++) {
						final int arrayIndex = rowIndex + x;
						if (workArray[z][arrayIndex] == phase) {
							particleLabels[z][arrayIndex] = ID;
							int minTag = ID;
							// Find the minimum particleLabel in the
							// neighbouring pixels
							int nX = x, nY = y, nZ = z;
							for (int n = 0; n < 7; n++) {
								switch (n) {
								case 0:
									break;
								case 1:
									nX = x - 1;
									break;
								case 2:
									nX = x + 1;
									break;
								case 3:
									nY = y - 1;
									nX = x;
									break;
								case 4:
									nY = y + 1;
									break;
								case 5:
									nZ = z - 1;
									nY = y;
									break;
								case 6:
									nZ = z + 1;
									break;
								}
								if (withinBounds(nX, nY, nZ, w, h, 0, d)) {
									final int offset = getOffset(nX, nY, w);
									if (workArray[nZ][offset] == phase) {
										final int tagv = particleLabels[nZ][offset];
										if (tagv != 0 && tagv < minTag) {
											minTag = tagv;
										}
									}
								}
							}
							// assign the smallest particle label from the
							// neighbours to the pixel
							particleLabels[z][arrayIndex] = minTag;
							// increment the particle label
							if (minTag == ID) {
								ID++;
							}
						}
					}
				}
				IJ.showProgress(z, d);
			}
			ID++;
		}
		return particleLabels;
	}

	/**
	 * Connect structures = minimisation of IDs
	 * 
	 * @param workArray
	 * @param particleLabels
	 * @param phase
	 *            foreground or background
	 * @param scanRanges
	 *            int[][] listing ranges to run connectStructures on
	 * @return particleLabels with all particles connected
	 */
	private void connectStructures(ImagePlus imp, final byte[][] workArray,
			int[][] particleLabels, final int phase, final int[][] scanRanges) {
		IJ.showStatus("Connecting " + sPhase + " structures" + chunkString);
		final int w = imp.getWidth();
		final int h = imp.getHeight();
		final int d = imp.getImageStackSize();
		for (int c = 0; c < scanRanges[0].length; c++) {
			final int sR0 = scanRanges[0][c];
			final int sR1 = scanRanges[1][c];
			final int sR2 = scanRanges[2][c];
			final int sR3 = scanRanges[3][c];
			if (phase == FORE) {
				for (int z = sR0; z < sR1; z++) {
					for (int y = 0; y < h; y++) {
						final int rowIndex = y * w;
						for (int x = 0; x < w; x++) {
							final int arrayIndex = rowIndex + x;
							if (workArray[z][arrayIndex] == phase
									&& particleLabels[z][arrayIndex] > 1) {
								int minTag = particleLabels[z][arrayIndex];
								// Find the minimum particleLabel in the
								// neighbours' pixels
								for (int vZ = z - 1; vZ <= z + 1; vZ++) {
									for (int vY = y - 1; vY <= y + 1; vY++) {
										for (int vX = x - 1; vX <= x + 1; vX++) {
											if (withinBounds(vX, vY, vZ, w, h,
													sR2, sR3)) {
												final int offset = getOffset(
														vX, vY, w);
												if (workArray[vZ][offset] == phase) {
													final int tagv = particleLabels[vZ][offset];
													if (tagv != 0
															&& tagv < minTag) {
														minTag = tagv;
													}
												}
											}
										}
									}
								}
								// Replacing particleLabel by the minimum
								// particleLabel found
								for (int vZ = z - 1; vZ <= z + 1; vZ++) {
									for (int vY = y - 1; vY <= y + 1; vY++) {
										for (int vX = x - 1; vX <= x + 1; vX++) {
											if (withinBounds(vX, vY, vZ, w, h,
													sR2, sR3)) {
												final int offset = getOffset(
														vX, vY, w);
												if (workArray[vZ][offset] == phase) {
													final int tagv = particleLabels[vZ][offset];
													if (tagv != 0
															&& tagv != minTag) {
														replaceLabel(
																particleLabels,
																tagv, minTag,
																sR2, sR3);
													}
												}
											}
										}
									}
								}
							}
						}
					}
					IJ.showStatus("Connecting foreground structures"
							+ chunkString);
					IJ.showProgress(z, d);
				}
			} else if (phase == BACK) {
				for (int z = sR0; z < sR1; z++) {
					for (int y = 0; y < h; y++) {
						final int rowIndex = y * w;
						for (int x = 0; x < w; x++) {
							final int arrayIndex = rowIndex + x;
							if (workArray[z][arrayIndex] == phase) {
								int minTag = particleLabels[z][arrayIndex];
								// Find the minimum particleLabel in the
								// neighbours' pixels
								int nX = x, nY = y, nZ = z;
								for (int n = 0; n < 7; n++) {
									switch (n) {
									case 0:
										break;
									case 1:
										nX = x - 1;
										break;
									case 2:
										nX = x + 1;
										break;
									case 3:
										nY = y - 1;
										nX = x;
										break;
									case 4:
										nY = y + 1;
										break;
									case 5:
										nZ = z - 1;
										nY = y;
										break;
									case 6:
										nZ = z + 1;
										break;
									}
									if (withinBounds(nX, nY, nZ, w, h, sR2, sR3)) {
										final int offset = getOffset(nX, nY, w);
										if (workArray[nZ][offset] == phase) {
											final int tagv = particleLabels[nZ][offset];
											if (tagv != 0 && tagv < minTag) {
												minTag = tagv;
											}
										}
									}
								}
								// Replacing particleLabel by the minimum
								// particleLabel found
								for (int n = 0; n < 7; n++) {
									switch (n) {
									case 0:
										nZ = z;
										break; // last switch block left nZ = z
									// + 1;
									case 1:
										nX = x - 1;
										break;
									case 2:
										nX = x + 1;
										break;
									case 3:
										nY = y - 1;
										nX = x;
										break;
									case 4:
										nY = y + 1;
										break;
									case 5:
										nZ = z - 1;
										nY = y;
										break;
									case 6:
										nZ = z + 1;
										break;
									}
									if (withinBounds(nX, nY, nZ, w, h, sR2, sR3)) {
										final int offset = getOffset(nX, nY, w);
										if (workArray[nZ][offset] == phase) {
											final int tagv = particleLabels[nZ][offset];
											if (tagv != 0 && tagv != minTag) {
												replaceLabel(particleLabels,
														tagv, minTag, sR2, sR3);
											}
										}
									}
								}
							}
						}
					}
					IJ.showStatus("Connecting background structures"
							+ chunkString);
					IJ.showProgress(z, d + 1);
				}
			}
		}
		return;
	}

	class ConnectStructuresThread extends Thread {
		final ImagePlus imp;

		final int thread, nThreads, nChunks, phase;

		final byte[][] workArray;

		final int[][] particleLabels;

		final int[][] chunkRanges;

		public ConnectStructuresThread(int thread, int nThreads, ImagePlus imp,
				byte[][] workArray, int[][] particleLabels, final int phase,
				int nChunks, int[][] chunkRanges) {
			this.imp = imp;
			this.thread = thread;
			this.nThreads = nThreads;
			this.workArray = workArray;
			this.particleLabels = particleLabels;
			this.phase = phase;
			this.nChunks = nChunks;
			this.chunkRanges = chunkRanges;
		}

		public void run() {
			for (int k = this.thread; k < this.nChunks; k += this.nThreads) {
				// assign singleChunkRange for chunk k from chunkRanges
				int[][] singleChunkRange = new int[4][1];
				for (int i = 0; i < 4; i++) {
					singleChunkRange[i][0] = this.chunkRanges[i][k];
				}
				chunkString = ": chunk " + (k + 1) + "/" + nChunks;
				connectStructures(this.imp, this.workArray,
						this.particleLabels, this.phase, singleChunkRange);
			}
		}
	}// ConnectStructuresThread

	/**
	 * Create a work array
	 * 
	 * @return byte[] work array
	 */
	private byte[][] makeWorkArray(ImagePlus imp) {
		final int s = imp.getStackSize();
		final int p = imp.getWidth() * imp.getHeight();
		byte[][] workArray = new byte[s][p];
		ImageStack stack = imp.getStack();
		for (int z = 0; z < s; z++) {
			ImageProcessor ip = stack.getProcessor(z + 1);
			for (int i = 0; i < p; i++) {
				workArray[z][i] = (byte) ip.get(i);
			}
		}
		return workArray;
	}

	/**
	 * Get a 2 d array that defines the z-slices to scan within while connecting
	 * particles within chunkified stacks.
	 * 
	 * @param nC
	 *            number of chunks
	 * @return scanRanges int[][] containing 4 limits: int[0][] - start of outer
	 *         for; int[1][] end of outer for; int[3][] start of inner for;
	 *         int[4] end of inner 4. Second dimension is chunk number.
	 */
	public int[][] getChunkRanges(ImagePlus imp, int nC, int slicesPerChunk) {
		final int nSlices = imp.getImageStackSize();
		int[][] scanRanges = new int[4][nC];
		scanRanges[0][0] = 0; // the first chunk starts at the first (zeroth)
		// slice
		scanRanges[2][0] = 0; // and that is what replaceLabel() will work on
		// first

		if (nC == 1) {
			scanRanges[1][0] = nSlices;
			scanRanges[3][0] = nSlices;
		} else if (nC > 1) {
			scanRanges[1][0] = slicesPerChunk;
			scanRanges[3][0] = slicesPerChunk;

			for (int c = 1; c < nC; c++) {
				for (int i = 0; i < 4; i++) {
					scanRanges[i][c] = scanRanges[i][c - 1] + slicesPerChunk;
				}
			}
			// reduce the last chunk to nSlices
			scanRanges[1][nC - 1] = nSlices;
			scanRanges[3][nC - 1] = nSlices;
		}
		return scanRanges;
	}

	/**
	 * Return scan ranges for stitching. The first 2 values for each chunk are
	 * the first slice of the next chunk and the last 2 values are the range
	 * through which to replaceLabels()
	 * 
	 * Running replace labels over incrementally increasing volumes as chunks
	 * are added is OK (for 1st interface connect chunks 0 & 1, for 2nd connect
	 * chunks 0, 1, 2, etc.)
	 * 
	 * @param nC
	 *            number of chunks
	 * @return scanRanges list of scan limits for connectStructures() to stitch
	 *         chunks back together
	 */
	private int[][] getStitchRanges(ImagePlus imp, int nC, int slicesPerChunk) {
		final int nSlices = imp.getImageStackSize();
		if (nC < 2) {
			return null;
		}
		int[][] scanRanges = new int[4][3 * (nC - 1)]; // there are nC - 1
		// interfaces

		for (int c = 0; c < nC - 1; c++) {
			scanRanges[0][c] = (c + 1) * slicesPerChunk;
			scanRanges[1][c] = (c + 1) * slicesPerChunk + 1;
			scanRanges[2][c] = c * slicesPerChunk; // forward and reverse
			// algorithm
			// scanRanges[2][c] = 0; //cumulative algorithm - reliable but O²
			// hard
			scanRanges[3][c] = (c + 2) * slicesPerChunk;
		}
		// stitch back
		for (int c = nC - 1; c < 2 * (nC - 1); c++) {
			scanRanges[0][c] = (2 * nC - c - 2) * slicesPerChunk - 1;
			scanRanges[1][c] = (2 * nC - c - 2) * slicesPerChunk;
			scanRanges[2][c] = (2 * nC - c - 3) * slicesPerChunk;
			scanRanges[3][c] = (2 * nC - c - 1) * slicesPerChunk;
		}
		// stitch forwards (paranoid third pass)
		for (int c = 2 * (nC - 1); c < 3 * (nC - 1); c++) {
			scanRanges[0][c] = (-2 * nC + c + 3) * slicesPerChunk;
			scanRanges[1][c] = (-2 * nC + c + 3) * slicesPerChunk + 1;
			scanRanges[2][c] = (-2 * nC + c + 2) * slicesPerChunk;
			scanRanges[3][c] = (-2 * nC + c + 4) * slicesPerChunk;
		}
		for (int i = 0; i < scanRanges.length; i++) {
			for (int c = 0; c < scanRanges[i].length; c++) {
				if (scanRanges[i][c] > nSlices) {
					scanRanges[i][c] = nSlices;
				}
			}
		}
		scanRanges[3][nC - 2] = nSlices;
		return scanRanges;
	}

	/**
	 * Check to see if the pixel at (m,n,o) is within the bounds of the current
	 * stack
	 * 
	 * @param m
	 *            x co-ordinate
	 * @param n
	 *            y co-ordinate
	 * @param o
	 *            z co-ordinate
	 * @param startZ
	 *            first Z coordinate to use
	 * 
	 * @param endZ
	 *            last Z coordinate to use
	 * 
	 * @return True if the pixel is within the bounds of the current stack
	 */
	private boolean withinBounds(int m, int n, int o, int w, int h, int startZ,
			int endZ) {
		return (m >= 0 && m < w && n >= 0 && n < h && o >= startZ && o < endZ);
	}

	/**
	 * Find the offset within a 1D array given 2 (x, y) offset values
	 * 
	 * @param m
	 *            x difference
	 * @param n
	 *            y difference
	 * 
	 * @return Integer offset for looking up pixel in work array
	 */
	private int getOffset(int m, int n, int w) {
		return m + n * w;
	}

	/**
	 * Check whole array replacing m with n
	 * 
	 * @param m
	 *            value to be replaced
	 * @param n
	 *            new value
	 * @param startZ
	 *            first z coordinate to check
	 * @param endZ
	 *            last+1 z coordinate to check
	 */
	public void replaceLabel(int[][] particleLabels, final int m, int n,
			int startZ, final int endZ) {
		final int s = particleLabels[0].length;
		for (int z = startZ; z < endZ; z++) {
			for (int i = 0; i < s; i++)
				if (particleLabels[z][i] == m) {
					particleLabels[z][i] = n;
				}
		}
	}

	/**
	 * Get the sizes of all the particles as a voxel count
	 * 
	 * @param particleLabels
	 * @return particleSizes
	 */
	public long[] getParticleSizes(final int[][] particleLabels) {
		IJ.showStatus("Getting " + sPhase + " particle sizes");
		final int d = particleLabels.length;
		final int wh = particleLabels[0].length;
		// find the highest value particleLabel
		int maxParticle = 0;
		for (int z = 0; z < d; z++) {
			for (int i = 0; i < wh; i++) {
				maxParticle = Math.max(maxParticle, particleLabels[z][i]);
			}
		}

		long[] particleSizes = new long[maxParticle + 1];
		for (int z = 0; z < d; z++) {
			for (int i = 0; i < wh; i++) {
				particleSizes[particleLabels[z][i]]++;
			}
			IJ.showProgress(z, d);
		}
		return particleSizes;
	}

	/**
	 * Display the particle labels as an ImagePlus
	 * 
	 * @param particleLabels
	 * @param imp
	 *            original image, used for image dimensions, calibration and
	 *            titles
	 */
	private ImagePlus displayParticleLabels(int[][] particleLabels,
			ImagePlus imp) {
		final int w = imp.getWidth();
		final int h = imp.getHeight();
		final int d = imp.getImageStackSize();
		final int wh = w * h;
		ImageStack stack = new ImageStack(w, h);
		double max = 0;
		for (int z = 0; z < d; z++) {
			float[] slicePixels = new float[wh];
			for (int i = 0; i < wh; i++) {
				slicePixels[i] = (float) particleLabels[z][i];
				max = Math.max(max, slicePixels[i]);
			}
			stack.addSlice(imp.getImageStack().getSliceLabel(z + 1),
					slicePixels);
		}
		ImagePlus impParticles = new ImagePlus(imp.getShortTitle() + "_parts",
				stack);
		impParticles.setCalibration(imp.getCalibration());
		impParticles.getProcessor().setMinAndMax(0, max);
		return impParticles;
	}
}
