/*********************
 This file is taken from the original CVC4 repository: https://github.com/CVC4/CVC4/blob/master/test/java/LinearArith.java
 Malte Mues adapted it to fit the cvc4-turnkey test framework.

 The original file is licensed under a 3-clause-BSD license: https://github.com/CVC4/CVC4/blob/master/COPYING
 We license the changes made also under the 3-clause-BSD license
/*! \file LinearArith.java
 ** \verbatim
 ** Top contributors (to current version):
 **   Pat Hawks, Andres Noetzli
 ** This file is part of the CVC4 project.
 ** Copyright (c) 2009-2020 by the authors listed in the file AUTHORS
 ** in the top-level source directory) and their institutional affiliations.
 ** All rights reserved.  See the file COPYING in the top-level source
 ** directory for licensing information.\endverbatim
 **
 ** \brief [[ Add one-line brief description here ]]
 **
 ** [[ Add lengthier description here ]]
 ** \todo document this file
 **/

package io.github.tudoaqua.cvc4turnkey;

import edu.stanford.CVC4.Expr;
import edu.stanford.CVC4.ExprManager;
import edu.stanford.CVC4.Kind;
import edu.stanford.CVC4.Rational;
import edu.stanford.CVC4.Result;
import edu.stanford.CVC4.SmtEngine;
import edu.stanford.CVC4.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LinearArithmetikTest {
	ExprManager em;
	SmtEngine smt;

	@BeforeEach
	public void initialize() {
		em = new ExprManager();
		smt = new SmtEngine(em);
	}

	@Test
	public void evaluatesExpression() {
		smt.setLogic("QF_LIRA"); // Set the logic

		// Prove that if given x (Integer) and y (Real) then
		// the maximum value of y - x is 2/3

		// Types
		Type real = em.realType();
		Type integer = em.integerType();

		// Variables
		Expr x = em.mkVar("x", integer);
		Expr y = em.mkVar("y", real);

		// Constants
		Expr three = em.mkConst(new Rational(3));
		Expr neg2 = em.mkConst(new Rational(-2));
		Expr two_thirds = em.mkConst(new Rational(2,3));

		// Terms
		Expr three_y = em.mkExpr(Kind.MULT, three, y);
		Expr diff = em.mkExpr(Kind.MINUS, y, x);

		// Formulas
		Expr x_geq_3y = em.mkExpr(Kind.GEQ, x, three_y);
		Expr x_leq_y = em.mkExpr(Kind.LEQ, x, y);
		Expr neg2_lt_x = em.mkExpr(Kind.LT, neg2, x);

		Expr assumptions =
				em.mkExpr(Kind.AND, x_geq_3y, x_leq_y, neg2_lt_x);
		smt.assertFormula(assumptions);
		smt.push();
		Expr diff_leq_two_thirds = em.mkExpr(Kind.LEQ, diff, two_thirds);

		assertEquals(Result.Entailment.ENTAILED,
					 smt.checkEntailed(diff_leq_two_thirds).isEntailed());

		smt.pop();

		smt.push();
		Expr diff_is_two_thirds = em.mkExpr(Kind.EQUAL, diff, two_thirds);
		smt.assertFormula(diff_is_two_thirds);

		assertEquals(
				Result.Sat.SAT,
				smt.checkSat(em.mkConst(true)).isSat()
		);

		smt.pop();
	}
}
