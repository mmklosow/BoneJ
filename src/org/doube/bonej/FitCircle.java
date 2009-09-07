package org.doube.bonej;

import ij.IJ;

import java.util.Arrays;

import Jama.Matrix;
import Jama.EigenvalueDecomposition;
import Jama.SingularValueDecomposition;

/**
 * Methods for fitting circles to coordinates
 * 
 * @author Michael Doube, ported from Nikolai Chernov's MATLAB scripts
 * @see <p>
 *      Chernov's paper and <a
 *      href="http://www.math.uab.edu/~chernov/cl/MATLABcircle.html"
 *      >http://www.math.uab.edu/~chernov/cl/MATLABcircle.html</a>
 *      </p>
 * 
 */
public class FitCircle {

    /**
     * Chernov's non-biased Hyper algebraic method. Stability optimised version.
     * 
     * @param points
     *            double[n][2] containing n (<i>x</i>, <i>y</i>) coordinates
     * @return 3-element double[] containing (<i>x</i>, <i>y</i>) centre and
     *         circle radius
     */
    public double[] hyperCircle(double[][] points) {
	int nPoints = points.length;
	double[] centroid = getCentroid(points);

	double[] x = new double[nPoints];
	double[] y = new double[nPoints];
	double[] z = new double[nPoints];
	double[] ones = new double[nPoints];
	Arrays.fill(ones, 1);

	double sumZ = 0;
	// centre data and assign vector values
	for (int n = 0; n < nPoints; n++) {
	    x[n] = points[n][0] - centroid[0];
	    y[n] = points[n][1] - centroid[1];
	    z[n] = x[n] * x[n] + y[n] * y[n];
	    sumZ += z[n];
	}
	double[][] xyz1 = { x, y, z, ones };
	Matrix XYZ1 = new Matrix(xyz1);
	SingularValueDecomposition svd = new SingularValueDecomposition(XYZ1);
	Matrix U = svd.getU();
	Matrix S = svd.getS();
	Matrix V = svd.getV();
	
	printMatrix(U, "U");
	printMatrix(S, "S");
	printMatrix(V, "V");
	
	Matrix A;
	
	// singular case
	if (S.get(4, 4) / S.get(1, 1) < 1e-12) {
	    double[][] v = V.getArray();
	    double[][] a = new double[v.length][1];
	    for (int i = 0; i < a.length; i++) {
		a[i][0] = v[i][4];
	    }
	    A = new Matrix(a);
	    printMatrix(A, "A");
	} else {
	    // regular case
	    Matrix Y = V.times(S.times(V));
	    printMatrix(Y, "Y");
	    
	    double[][] bInv = { { 0, 0, 0, 0.5 }, { 0, 1, 0, 0 },
		    { 0, 0, 1, 0 }, { 0.5, 0, 0, -2 * sumZ / nPoints } };
	    Matrix Binv = new Matrix(bInv);
	    printMatrix(Binv, "Binv");
	    
	    EigenvalueDecomposition ED = new EigenvalueDecomposition(Y.times(Binv.times(Y.transpose())));
	    Matrix D = ED.getD(); //eigenvalues
	    Matrix E = ED.getV(); //eigenvectors
	    
	    printMatrix(D, "D");
	    printMatrix(E, "E");
	    
	    //    [Dsort,ID] = sort(diag(D));
	    //	    A = E(:,ID(2));
	    //I think these 2 likes mean "Make an array, A, out of the eigenvector
	    //corresponding to the 2nd biggest eigenvalue"
	    //Jama's eigenvalues are ordered, so don't have to do this.
	    
	    //get the 2nd column from eigenvectors
	    A = E.getMatrix(0, E.getRowDimension(), 1, 1);
	    printMatrix(A, "A");
	}
	double[] centreRadius = new double[3];
	return centreRadius;
    }

    private double[] getCentroid(double[][] points) {
	double[] centroid = new double[2];
	double sumX = 0;
	double sumY = 0;
	int nPoints = points.length;

	for (int n = 0; n < nPoints; n++) {
	    sumX += points[n][0];
	    sumY += points[n][1];
	}

	centroid[0] = sumX / nPoints;
	centroid[1] = sumY / nPoints;

	return centroid;
    }
    
    public void printMatrix(Matrix matrix, String title){
	IJ.log(title);
	int nCols = matrix.getColumnDimension();
	int nRows = matrix.getRowDimension();
	double[][] eVal = matrix.getArrayCopy();
	for (int r = 0; r < nRows; r++){
	    String row = "||";
	    for (int c = 0; c < nCols; c++){
		row = row + eVal[r][c] + "|";
	    }
	    row = row + "|";
	    IJ.log(row);
	}
	return;
    }

}