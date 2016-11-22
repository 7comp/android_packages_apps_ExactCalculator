/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.calculator2;

import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.widget.TextView;

/**
 * Contains the logic for animating the recyclerview elements on drag.
 */
public final class DragController {

    private static final String TAG = "DragController";

    // References to views from the Calculator Display.
    private CalculatorFormula mDisplayFormula;
    private CalculatorResult mDisplayResult;
    private View mToolbar;

    private int mFormulaTranslationY;
    private int mFormulaTranslationX;
    private float mFormulaScale;
    private float mResultScale;

    private int mResultTranslationY;
    private int mResultTranslationX;

    private int mDisplayHeight;

    private boolean mAnimationInitialized;

    private AnimationController mAnimationController;

    private Evaluator mEvaluator;

    public void setEvaluator(Evaluator evaluator) {
        mEvaluator = evaluator;

        if (evaluator != null) {
            // Initialize controller
            if (EvaluatorStateUtils.isDisplayEmpty(mEvaluator)) {
                // Empty display
                mAnimationController = new EmptyAnimationController();
            } else if (isResultState()) {
                // Result
                mAnimationController = new ResultAnimationController();
            } else {
                // There is something in the formula field. There may or may not be
                // a quick result.
                mAnimationController = new AnimationController();
            }
        }
    }

    private boolean isResultState() {
        return mDisplayResult.getTranslationY() != 0;
    }

    public void setDisplayFormula(CalculatorFormula formula) {
        mDisplayFormula = formula;
    }

    public void setDisplayResult(CalculatorResult result) {
        mDisplayResult = result;
    }

    public void setToolbar(View toolbar) {
        mToolbar = toolbar;
    }

    public void animateViews(float yFraction, RecyclerView recyclerView, int itemCount) {
        final HistoryAdapter.ViewHolder vh = (HistoryAdapter.ViewHolder)
                recyclerView.findViewHolderForAdapterPosition(0);
        if (vh != null && !EvaluatorStateUtils.isDisplayEmpty(mEvaluator)) {
            final CalculatorFormula formula = vh.getFormula();
            final CalculatorResult result = vh.getResult();
            final TextView date = vh.getDate();

            if (!mAnimationInitialized) {
                mAnimationController.initializeScales(formula, result);

                mAnimationController.initializeFormulaTranslationX(formula);

                mAnimationController.initializeFormulaTranslationY(formula, result);

                mAnimationController.initializeResultTranslationX(result);

                mAnimationController.initializeResultTranslationY(result);

                mAnimationInitialized = true;
            }

            if (mAnimationInitialized) {
                result.setScaleX(mAnimationController.getResultScale(yFraction));
                result.setScaleY(mAnimationController.getResultScale(yFraction));

                formula.setScaleX(mAnimationController.getFormulaScale(yFraction));
                formula.setScaleY(mAnimationController.getFormulaScale(yFraction));

                formula.setPivotX(formula.getWidth() - formula.getPaddingEnd());
                formula.setPivotY(formula.getHeight() - formula.getPaddingBottom());

                result.setPivotX(result.getWidth() - result.getPaddingEnd());
                result.setPivotY(result.getHeight() - result.getPaddingBottom());

                formula.setTranslationX(mAnimationController.getFormulaTranslationX(yFraction));
                formula.setTranslationY(mAnimationController.getFormulaTranslationY(yFraction));

                result.setTranslationX(mAnimationController.getResultTranslationX(yFraction));
                result.setTranslationY(mAnimationController.getResultTranslationY(yFraction));

                date.setTranslationY(mAnimationController.getDateTranslationY(yFraction));
            }
        } else if (EvaluatorStateUtils.isDisplayEmpty(mEvaluator)) {
            // There is no current expression but we still need to collect information
            // to translate the other viewholders.
            if (!mAnimationInitialized) {
                mAnimationController.initializeDisplayHeight();

                mAnimationInitialized = true;
            }
        }

        // Move up all ViewHolders above the current expression; if there is no current expression,
        // we're translating all the viewholders.
        for (int i = recyclerView.getChildCount() - 1;
             i >= mAnimationController.getFirstTranslatedViewHolderIndex();
             --i) {
            final RecyclerView.ViewHolder vh2 =
                    recyclerView.getChildViewHolder(recyclerView.getChildAt(i));
            if (vh2 != null) {
                final View view = vh2.itemView;
                if (view != null) {
                    view.setTranslationY(
                        mAnimationController.getHistoryElementTranslationY(yFraction));
                }
            }
        }
    }

    /**
     * Reset all initialized values whenever the History fragment is closed because the
     * DisplayState may change.
     */
    public void resetAnimationInitialized() {
        mAnimationInitialized = false;
    }

    public interface AnimateTextInterface {

        void initializeDisplayHeight();

        void initializeScales(CalculatorFormula formula, CalculatorResult result);

        void initializeFormulaTranslationX(CalculatorFormula formula);

        void initializeFormulaTranslationY(CalculatorFormula formula, CalculatorResult result);

        void initializeResultTranslationX(CalculatorResult result);

        void initializeResultTranslationY(CalculatorResult result);

        float getResultTranslationX(float yFraction);

        float getResultTranslationY(float yFraction);

        float getResultScale(float yFraction);

        float getFormulaScale(float yFraction);

        float getFormulaTranslationX(float yFraction);

        float getFormulaTranslationY(float yFraction);

        float getDateTranslationY(float yFraction);

        float getHistoryElementTranslationY(float yFraction);

        // Return the lowest index of the first Viewholder to be translated upwards.
        // If there is no current expression, we translate all the viewholders; otherwise,
        // we start at index 1.
        int getFirstTranslatedViewHolderIndex();
    }

    // The default AnimationController when Display is in INPUT state and DisplayFormula is not
    // empty. There may or may not be a quick result.
    public class AnimationController implements DragController.AnimateTextInterface {

        public void initializeDisplayHeight() {
            // no-op
        }

        public void initializeScales(CalculatorFormula formula, CalculatorResult result) {
            // Calculate the scale for the text
            mFormulaScale = (mDisplayFormula.getTextSize() * 1.0f) / formula.getTextSize();
        }

        public void initializeFormulaTranslationY(CalculatorFormula formula,
                CalculatorResult result) {
            // Baseline of formula moves by the difference in formula bottom padding and the
            // difference in result height.
            mFormulaTranslationY =
                    mDisplayFormula.getPaddingBottom() - formula.getPaddingBottom()
                            + mDisplayResult.getHeight() - result.getHeight();

        }

        public void initializeFormulaTranslationX(CalculatorFormula formula) {
            // Right border of formula moves by the difference in formula end padding.
            mFormulaTranslationX = mDisplayFormula.getPaddingEnd() - formula.getPaddingEnd();
        }

        public void initializeResultTranslationY(CalculatorResult result) {
            // Baseline of result moves by the difference in result bottom padding.
            mResultTranslationY = mDisplayResult.getPaddingBottom() - result.getPaddingBottom();
        }

        public void initializeResultTranslationX(CalculatorResult result) {
            mResultTranslationX = mDisplayResult.getPaddingEnd() - result.getPaddingEnd();
        }

        public float getResultTranslationX(float yFraction) {
            return (mResultTranslationX * yFraction) - mResultTranslationX;
        }

        public float getResultTranslationY(float yFraction) {
            return (mResultTranslationY * yFraction) - mResultTranslationY;
        }

        public float getResultScale(float yFraction) {
            return 1;
        }

        public float getFormulaScale(float yFraction) {
            return mFormulaScale - (mFormulaScale * yFraction) + yFraction;
        }

        public float getFormulaTranslationX(float yFraction) {
            return (mFormulaTranslationX * yFraction) -
                    mFormulaTranslationX;
        }

        public float getFormulaTranslationY(float yFraction) {
            // Scale linearly between -FormulaTranslationY and 0.
            return (mFormulaTranslationY * yFraction) - mFormulaTranslationY;
        }

        public float getDateTranslationY(float yFraction) {
            // We also want the date to start out above the visible screen with
            // this distance decreasing as it's pulled down.
            return -mToolbar.getHeight() * (1 - yFraction)
                    + getResultTranslationY(yFraction)
                    - mDisplayFormula.getPaddingTop() +
                    (mDisplayFormula.getPaddingTop() * yFraction);
        }

        public float getHistoryElementTranslationY(float yFraction) {
            return getDateTranslationY(yFraction);
        }

        public int getFirstTranslatedViewHolderIndex() {
            return 1;
        }
    }

    // The default AnimationController when Display is in RESULT state.
    public class ResultAnimationController extends AnimationController
            implements DragController.AnimateTextInterface {
        @Override
        public void initializeScales(CalculatorFormula formula, CalculatorResult result) {
            final float textSize = mDisplayResult.getTextSize() * mDisplayResult.getScaleX();
            mResultScale = textSize / result.getTextSize();

            mFormulaScale = 1;
        }

        @Override
        public void initializeFormulaTranslationY(CalculatorFormula formula,
                CalculatorResult result) {
            // Baseline of formula moves by the difference in formula bottom padding and the
            // difference in the result height.
            mFormulaTranslationY = mDisplayFormula.getPaddingBottom() - formula.getPaddingBottom()
                            + mDisplayResult.getHeight() - result.getHeight();
        }

        @Override
        public void initializeFormulaTranslationX(CalculatorFormula formula) {
            // Right border of formula moves by the difference in formula end padding.
            mFormulaTranslationX = mDisplayFormula.getPaddingEnd() - formula.getPaddingEnd();
        }

        @Override
        public void initializeResultTranslationY(CalculatorResult result) {
            // Baseline of result moves by the difference in result bottom padding.
            mResultTranslationY = mDisplayResult.getBottom() - result.getBottom() +
                    mDisplayResult.getPaddingBottom() - result.getPaddingBottom();
        }

        @Override
        public void initializeResultTranslationX(CalculatorResult result) {
            mResultTranslationX = mDisplayResult.getPaddingEnd() - result.getPaddingEnd();
        }

        @Override
        public float getResultTranslationX(float yFraction) {
            return (mResultTranslationX * yFraction) - mResultTranslationX;
        }

        @Override
        public float getResultTranslationY(float yFraction) {
            return (mResultTranslationY * yFraction) - mResultTranslationY;
        }

        @Override
        public float getFormulaTranslationX(float yFraction) {
            return (mFormulaTranslationX * yFraction) -
                    mFormulaTranslationX;
        }

        @Override
        public float getFormulaTranslationY(float yFraction) {
            return getDateTranslationY(yFraction);
        }

        @Override
        public float getResultScale(float yFraction) {
            return mResultScale - (mResultScale * yFraction) + yFraction;
        }

        @Override
        public float getFormulaScale(float yFraction) {
            return 1;
        }

        @Override
        public float getDateTranslationY(float yFraction) {
            // We also want the date to start out above the visible screen with
            // this distance decreasing as it's pulled down.
            return -mToolbar.getHeight() * (1 - yFraction)
                    + (mResultTranslationY * yFraction) - mResultTranslationY
                    - mDisplayFormula.getPaddingTop() +
                    (mDisplayFormula.getPaddingTop() * yFraction);
        }

        @Override
        public int getFirstTranslatedViewHolderIndex() {
            return 1;
        }
    }

    // The default AnimationController when Display is completely empty.
    public class EmptyAnimationController extends AnimationController
            implements DragController.AnimateTextInterface {
        @Override
        public void initializeDisplayHeight() {
            mDisplayHeight = mToolbar.getHeight() + mDisplayResult.getHeight()
                    + mDisplayFormula.getHeight();
        }

        @Override
        public void initializeScales(CalculatorFormula formula, CalculatorResult result) {
            // no-op
        }

        @Override
        public void initializeFormulaTranslationY(CalculatorFormula formula,
                CalculatorResult result) {
            // no-op
        }

        @Override
        public void initializeFormulaTranslationX(CalculatorFormula formula) {
            // no-op
        }

        @Override
        public void initializeResultTranslationY(CalculatorResult result) {
            // no-op
        }

        @Override
        public void initializeResultTranslationX(CalculatorResult result) {
            // no-op
        }

        @Override
        public float getResultTranslationX(float yFraction) {
            return 0;
        }

        @Override
        public float getResultTranslationY(float yFraction) {
            return 0;
        }

        @Override
        public float getFormulaScale(float yFraction) {
            return 1;
        }

        @Override
        public float getDateTranslationY(float yFraction) {
            return 0;
        }

        @Override
        public float getHistoryElementTranslationY(float yFraction) {
            return -mDisplayHeight * (1 - yFraction);
        }

        @Override
        public int getFirstTranslatedViewHolderIndex() {
            return 0;
        }
    }
}
