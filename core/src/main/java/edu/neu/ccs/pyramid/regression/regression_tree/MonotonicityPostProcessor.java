package edu.neu.ccs.pyramid.regression.regression_tree;

import edu.neu.ccs.pyramid.util.MathUtil;
import edu.neu.ccs.pyramid.util.Pair;
import edu.neu.ccs.pyramid.util.PrintUtil;
import org.ojalgo.matrix.store.PhysicalStore;
import org.ojalgo.matrix.store.PrimitiveDenseStore;
import org.ojalgo.optimisation.Optimisation;
import org.ojalgo.optimisation.convex.ConvexSolver;

import java.util.List;

public class MonotonicityPostProcessor{



    /**
     * use quadratic programming to find leaf nodes outputs
     * @param leaves
     * @param monotonicity
     */
    public static void changeOutput(List<Node> leaves, int[] monotonicity, boolean strongConstraint){

        List<Pair<Integer,Integer>> constraints = MonotonicityConstraintFinder.findComparablePairs(leaves, monotonicity, strongConstraint);
        System.setProperty("shut.up.ojAlgo","true");

        List<Pair<Integer,Integer>> violatingPairs = MonotonicityConstraintFinder.findViolatingPairs(leaves, monotonicity, strongConstraint);
        if (violatingPairs.isEmpty()){
//            System.out.println("already monotonic");
            return;
        }

//        if (!violatingPairs.isEmpty()){
//            System.out.println("NOT monotonic");
//        }
//        System.out.println("constraints = "+constraints);
//        for (Pair<Integer,Integer> pair: constraints){
//            System.out.println("-----------------");
//            Bounds bound1 = new Bounds(leaves.get(pair.getFirst()));
//            Bounds bound2 = new Bounds(leaves.get(pair.getSecond()));
//            System.out.println("smaller node "+pair.getFirst()+" = "+bound1);
//            System.out.println("bigger node "+pair.getSecond()+" = "+bound2);
//            System.out.println("-----------------");
//        }


//        System.out.println("violating pairs = "+violatingPairs);

        PhysicalStore.Factory<Double, PrimitiveDenseStore> storeFactory = PrimitiveDenseStore.FACTORY;
        int numNodes = leaves.size();
        double[] counts = new double[numNodes];
        for (int i=0;i<leaves.size();i++){
            counts[i] = MathUtil.arraySum(leaves.get(i).getProbs());
        }

        double[] means = new double[numNodes];
        for (int i=0;i<leaves.size();i++){
            means[i] = leaves.get(i).getValue();
        }
//        System.out.println("before fixing");
//        System.out.println(PrintUtil.printWithIndex(means));

        PrimitiveDenseStore Q = storeFactory.makeZero(numNodes,numNodes);
        for (int i=0;i<numNodes;i++){
            Q.add(i,i,counts[i]);
        }

        PrimitiveDenseStore C = storeFactory.makeZero(numNodes,1);
        for (int i=0;i<numNodes;i++){
            C.add(i,0,counts[i]*means[i]);
        }

        PrimitiveDenseStore A = storeFactory.makeZero(constraints.size(),numNodes);
        for (int c=0;c<constraints.size();c++){
            Pair<Integer,Integer> pair = constraints.get(c);
            int smaller = pair.getFirst();
            int bigger = pair.getSecond();
            A.add(c,smaller,1);
            A.add(c,bigger,-1);
        }


        PrimitiveDenseStore b = storeFactory.makeZero(constraints.size(),1);
        Optimisation.Options options = new Optimisation.Options();
//        options.time_abort = 60000;
        options.iterations_abort=1000;
        options.iterations_suffice=1000;

        ConvexSolver convexSolver = ConvexSolver.getBuilder()
                .objective(Q,C)
                .inequalities(A,b)
                .build(options);



        Optimisation.Result result = convexSolver.solve();

        for (int i=0;i<leaves.size();i++){
            Node leaf = leaves.get(i);
            leaf.setValue(result.doubleValue(i));
        }

//        System.out.println("after fixing");
//        System.out.println(PrintUtil.printWithIndex(leaves.stream().mapToDouble(Node::getValue).toArray()));
//        if (!MonotonicityConstraintFinder.isMonotonic(leaves, monotonicity, strongConstraint)){
//            System.out.println("STILL NOT MONOTONIC!");
//            System.out.println("violating pairs");
//            System.out.println(PrintUtil.printWithIndex(MonotonicityConstraintFinder.findViolatingPairs(leaves, monotonicity, strongConstraint)));
//        }
    }


}
