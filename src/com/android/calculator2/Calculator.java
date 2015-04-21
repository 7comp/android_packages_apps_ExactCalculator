/*
 * Copyright (C) 2014 The Android Open Source Project
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

// FIXME: Menu handling, particularly for cut/paste, is very ugly
//        and not the way it was intended.
//        Other menus are not handled brilliantly either.
// TODO: Revisit handling of "Help" menu, so that it's more consistent
//       with our conventions.
// TODO: See if we can make scrolling look better, especially on small
//       displays. Fix evaluation interface so the evaluator returns entire
//       result, and formatting of exponent etc. is done separately.
// TODO: Better indication of when the result is known to be exact.
// TODO: Fix placement of inverse trig buttons.
// TODO: Check and possibly fix accessability issues.
// TODO: Copy & more general paste in formula?  Note that this requires
//       great care: Currently the text version of a displayed formula
//       is not directly useful for re-evaluating the formula later, since
//       it contains ellipses representing subexpressions evaluated with
//       a different degree mode.  Rather than supporting copy from the
//       formula window, we may eventually want to support generation of a
//       more useful text version in a separate window.  It's not clear
//       this is worth the added (code and user) complexity.

package com.android.calculator2;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.view.ViewPager;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.View.OnLongClickListener;
import android.view.ViewAnimationUtils;
import android.view.ViewGroupOverlay;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toolbar;

import com.android.calculator2.CalculatorEditText.OnTextSizeChangeListener;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.IOException;
import java.text.DecimalFormatSymbols;  // TODO: May eventually not need this here.

public class Calculator extends Activity
        implements OnTextSizeChangeListener, OnLongClickListener, CalculatorEditText.PasteListener {

    /**
     * Constant for an invalid resource id.
     */
    public static final int INVALID_RES_ID = -1;

    private enum CalculatorState {
        INPUT,          // Result and formula both visible, no evaluation requested,
                        // Though result may be visible on bottom line.
        EVALUATE,       // Both visible, evaluation requested, evaluation/animation incomplete.
        INIT,           // Very temporary state used as alternative to EVALUATE
                        // during reinitialization.  Do not animate on completion.
        ANIMATE,        // Result computed, animation to enlarge result window in progress.
        RESULT,         // Result displayed, formula invisible.
                        // If we are in RESULT state, the formula was evaluated without
                        // error to initial precision.
        ERROR           // Error displayed: Formula visible, result shows error message.
                        // Display similar to INPUT state.
    }
    // Normal transition sequence is
    // INPUT -> EVALUATE -> ANIMATE -> RESULT (or ERROR) -> INPUT
    // A RESULT -> ERROR transition is possible in rare corner cases, in which
    // a higher precision evaluation exposes an error.  This is possible, since we
    // initially evaluate assuming we were given a well-defined problem.  If we
    // were actually asked to compute sqrt(<extremely tiny negative number>) we produce 0
    // unless we are asked for enough precision that we can distinguish the argument from zero.
    // TODO: Consider further heuristics to reduce the chance of observing this?
    //       It already seems to be observable only in contrived cases.
    // ANIMATE, ERROR, and RESULT are translated to an INIT state if the application
    // is restarted in that state.  This leads us to recompute and redisplay the result
    // ASAP.
    // TODO: Possibly save a bit more information, e.g. its initial display string
    // or most significant digit position, to speed up restart.

    // We currently assume that the formula does not change out from under us in
    // any way. We explicitly handle all input to the formula here.
    // TODO: Perhaps the formula should not be editable at all?

    private final OnKeyListener mFormulaOnKeyListener = new OnKeyListener() {
        @Override
        public boolean onKey(View view, int keyCode, KeyEvent keyEvent) {
            if (keyEvent.getAction() != KeyEvent.ACTION_UP) return true;
            switch (keyCode) {
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                    mCurrentButton = mEqualButton;
                    onEquals();
                    return true;
                case KeyEvent.KEYCODE_DEL:
                    mCurrentButton = mDeleteButton;
                    onDelete();
                    return true;
                default:
                    final int raw = keyEvent.getKeyCharacterMap()
                          .get(keyCode, keyEvent.getMetaState());
                    if ((raw & KeyCharacterMap.COMBINING_ACCENT) != 0) {
                        return true; // discard
                    }
                    // Try to discard non-printing characters and the like.
                    // The user will have to explicitly delete other junk that gets past us.
                    if (Character.isIdentifierIgnorable(raw)
                        || Character.isWhitespace(raw)) {
                        return true;
                    }
                    char c = (char)raw;
                    if (c == '=') {
                        onEquals();
                    } else {
                        addChars(String.valueOf(c));
                        redisplayAfterFormulaChange();
                    }
            }
            return false;
        }
    };

    private static final String NAME = Calculator.class.getName();
    private static final String KEY_DISPLAY_STATE = NAME + "_display_state";
    private static final String KEY_UNPROCESSED_CHARS = NAME + "_unprocessed_chars";
    private static final String KEY_EVAL_STATE = NAME + "_eval_state";
                // Associated value is a byte array holding both mCalculatorState
                // and the (much more complex) evaluator state.

    private CalculatorState mCurrentState;
    private Evaluator mEvaluator;

    private View mDisplayView;
    private TextView mModeView;
    private CalculatorEditText mFormulaEditText;
    private CalculatorResult mResult;

    private ViewPager mPadViewPager;
    private View mDeleteButton;
    private View mClearButton;
    private View mEqualButton;
    private TextView mModeButton;

    private View mCurrentButton;
    private Animator mCurrentAnimator;

    private String mUnprocessedChars = null;   // Characters that were recently entered
                                               // at the end of the display that have not yet
                                               // been added to the underlying expression.

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calculator);
        setActionBar((Toolbar) findViewById(R.id.toolbar));

        // Hide all default options in the ActionBar.
        getActionBar().setDisplayOptions(0);

        mDisplayView = findViewById(R.id.display);
        mModeView = (TextView) findViewById(R.id.deg_rad);
        mFormulaEditText = (CalculatorEditText) findViewById(R.id.formula);
        mResult = (CalculatorResult) findViewById(R.id.result);

        mPadViewPager = (ViewPager) findViewById(R.id.pad_pager);
        mDeleteButton = findViewById(R.id.del);
        mClearButton = findViewById(R.id.clr);
        mEqualButton = findViewById(R.id.pad_numeric).findViewById(R.id.eq);
        if (mEqualButton == null || mEqualButton.getVisibility() != View.VISIBLE) {
            mEqualButton = findViewById(R.id.pad_operator).findViewById(R.id.eq);
        }
        mModeButton = (TextView) findViewById(R.id.mode_deg_rad);

        mEvaluator = new Evaluator(this, mResult);
        mResult.setEvaluator(mEvaluator);
        KeyMaps.setActivity(this);

        if (savedInstanceState != null) {
            setState(CalculatorState.values()[
                savedInstanceState.getInt(KEY_DISPLAY_STATE,
                                          CalculatorState.INPUT.ordinal())]);
            CharSequence unprocessed = savedInstanceState.getCharSequence(KEY_UNPROCESSED_CHARS);
            if (unprocessed != null) {
                mUnprocessedChars = unprocessed.toString();
            }
            byte[] state = savedInstanceState.getByteArray(KEY_EVAL_STATE);
            if (state != null) {
                try (ObjectInput in = new ObjectInputStream(new ByteArrayInputStream(state))) {
                    mEvaluator.restoreInstanceState(in);
                } catch (Throwable ignored) {
                    // When in doubt, revert to clean state
                    mCurrentState = CalculatorState.INPUT;
                    mEvaluator.clear();
                }
            }
        }
        mFormulaEditText.setOnKeyListener(mFormulaOnKeyListener);
        mFormulaEditText.setOnTextSizeChangeListener(this);
        mFormulaEditText.setPasteListener(this);
        mDeleteButton.setOnLongClickListener(this);
        updateDegreeMode(mEvaluator.getDegreeMode());
        if (mCurrentState == CalculatorState.EVALUATE) {
            // Odd case.  Evaluation probably took a long time.  Let user ask for it again
            mCurrentState = CalculatorState.INPUT;
            // TODO: This can happen if the user rotates the screen.
            // Is this rotate-to-abort behavior correct?  Revisit after experimentation.
        }
        if (mCurrentState != CalculatorState.INPUT) {
            setState(CalculatorState.INIT);
            mEvaluator.requireResult();
        } else {
            redisplayAfterFormulaChange();
        }
        // TODO: We're currently not saving and restoring scroll position.
        //       We probably should.  Details may require care to deal with:
        //         - new display size
        //         - slow recomputation if we've scrolled far.
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        // If there's an animation in progress, cancel it first to ensure our state is up-to-date.
        if (mCurrentAnimator != null) {
            mCurrentAnimator.cancel();
        }

        super.onSaveInstanceState(outState);
        outState.putInt(KEY_DISPLAY_STATE, mCurrentState.ordinal());
        outState.putCharSequence(KEY_UNPROCESSED_CHARS, mUnprocessedChars);
        ByteArrayOutputStream byteArrayStream = new ByteArrayOutputStream();
        try (ObjectOutput out = new ObjectOutputStream(byteArrayStream)) {
            mEvaluator.saveInstanceState(out);
        } catch (IOException e) {
            // Impossible; No IO involved.
            throw new AssertionError("Impossible IO exception", e);
        }
        outState.putByteArray(KEY_EVAL_STATE, byteArrayStream.toByteArray());
    }

    // Set the state, updating delete label and display colors.
    // This restores display positions on moving to INPUT.
    // But movement/animation for moving to RESULT has already been done.
    private void setState(CalculatorState state) {
        if (mCurrentState != state) {
            if (state == CalculatorState.INPUT) {
                restoreDisplayPositions();
            }
            mCurrentState = state;

            if (mCurrentState == CalculatorState.RESULT) {
                // No longer do this for ERROR; allow mistakes to be corrected.
                mDeleteButton.setVisibility(View.GONE);
                mClearButton.setVisibility(View.VISIBLE);
            } else {
                mDeleteButton.setVisibility(View.VISIBLE);
                mClearButton.setVisibility(View.GONE);
            }

            if (mCurrentState == CalculatorState.ERROR) {
                final int errorColor = getResources().getColor(R.color.calculator_error_color);
                mFormulaEditText.setTextColor(errorColor);
                mResult.setTextColor(errorColor);
                getWindow().setStatusBarColor(errorColor);
            } else {
                mFormulaEditText.setTextColor(
                        getResources().getColor(R.color.display_formula_text_color));
                mResult.setTextColor(
                        getResources().getColor(R.color.display_result_text_color));
                getWindow().setStatusBarColor(
                        getResources().getColor(R.color.calculator_accent_color));
            }

            invalidateOptionsMenu();
        }
    }

    @Override
    public void onBackPressed() {
        if (mPadViewPager == null || mPadViewPager.getCurrentItem() == 0) {
            // If the user is currently looking at the first pad (or the pad is not paged),
            // allow the system to handle the Back button.
            super.onBackPressed();
        } else {
            // Otherwise, select the previous pad.
            mPadViewPager.setCurrentItem(mPadViewPager.getCurrentItem() - 1);
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();

        // If there's an animation in progress, cancel it so the user interaction can be handled
        // immediately.
        if (mCurrentAnimator != null) {
            mCurrentAnimator.cancel();
        }
    }

    // Update the top corner degree/radian display and mode button
    // to reflect the indicated current degree mode (true = degrees)
    // TODO: Hide the top corner display until the advanced panel is exposed.
    private void updateDegreeMode(boolean dm) {
        if (dm) {
            mModeView.setText(R.string.mode_deg);
            mModeButton.setText(R.string.mode_rad);
            mModeButton.setContentDescription(getString(R.string.desc_mode_rad));
        } else {
            mModeView.setText(R.string.mode_rad);
            mModeButton.setText(R.string.mode_deg);
            mModeButton.setContentDescription(getString(R.string.desc_mode_deg));
        }
    }

    // Add the given button id to input expression.
    // If appropriate, clear the expression before doing so.
    private void addKeyToExpr(int id) {
        if (mCurrentState == CalculatorState.ERROR) {
            setState(CalculatorState.INPUT);
        } else if (mCurrentState == CalculatorState.RESULT) {
            if (KeyMaps.isBinary(id) || KeyMaps.isSuffix(id)) {
                mEvaluator.collapse();
            } else {
                mEvaluator.clear();
            }
            setState(CalculatorState.INPUT);
        }
        if (!mEvaluator.append(id)) {
            // TODO: Some user visible feedback?
        }
    }

    private void redisplayAfterFormulaChange() {
        // TODO: Could do this more incrementally.
        redisplayFormula();
        setState(CalculatorState.INPUT);
        mResult.clear();
        mEvaluator.evaluateAndShowResult();
    }

    public void onButtonClick(View view) {
        mCurrentButton = view;

        // Always cancel in-progress evaluation.
        // If we were waiting for the result, do nothing else.
        mEvaluator.cancelAll();

        if (mCurrentState == CalculatorState.EVALUATE
                || mCurrentState == CalculatorState.ANIMATE) {
            onCancelled();
            return;
        }


        final int id = view.getId();
        switch (id) {
            case R.id.eq:
                onEquals();
                break;
            case R.id.del:
                onDelete();
                break;
            case R.id.clr:
                onClear();
                break;
            case R.id.mode_deg_rad:
                boolean mode = !mEvaluator.getDegreeMode();
                updateDegreeMode(mode);
                if (mCurrentState == CalculatorState.RESULT) {
                    mEvaluator.collapse();  // Capture result evaluated in old mode
                    redisplayFormula();
                }
                // In input mode, we reinterpret already entered trig functions.
                mEvaluator.setDegreeMode(mode);
                setState(CalculatorState.INPUT);
                mResult.clear();
                mEvaluator.evaluateAndShowResult();
                break;
            default:
                addKeyToExpr(id);
                redisplayAfterFormulaChange();
                break;
        }
    }

    void redisplayFormula() {
        String formula = mEvaluator.getExpr().toString(this);
        if (mUnprocessedChars != null) {
            // Add and highlight characters we couldn't process.
            SpannableString formatted = new SpannableString(formula + mUnprocessedChars);
            // TODO: should probably match this to the error color.
            formatted.setSpan(new ForegroundColorSpan(Color.RED),
                              formula.length(), formatted.length(),
                              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            mFormulaEditText.setText(formatted);
        } else {
            mFormulaEditText.setText(formula);
        }
    }

    @Override
    public boolean onLongClick(View view) {
        mCurrentButton = view;

        if (view.getId() == R.id.del) {
            onClear();
            return true;
        }
        return false;
    }

    // Initial evaluation completed successfully.  Initiate display.
    public void onEvaluate(int initDisplayPrec, String truncatedWholeNumber) {
        // Invalidate any options that may depend on the current result.
        invalidateOptionsMenu();

        if (mCurrentState == CalculatorState.INPUT) {
            // Just update small result display.
            mResult.displayResult(initDisplayPrec, truncatedWholeNumber);
        } else { // in EVALUATE or INIT state
            mResult.displayResult(initDisplayPrec, truncatedWholeNumber);
            onResult(mCurrentState != CalculatorState.INIT);
        }
    }

    public void onCancelled() {
        // We should be in EVALUATE state.
        // Display is still in input state.
        setState(CalculatorState.INPUT);
    }

    // Reevaluation completed; ask result to redisplay current value.
    public void onReevaluate()
    {
        mResult.redisplay();
    }

    @Override
    public void onTextSizeChanged(final TextView textView, float oldSize) {
        if (mCurrentState != CalculatorState.INPUT) {
            // Only animate text changes that occur from user input.
            return;
        }

        // Calculate the values needed to perform the scale and translation animations,
        // maintaining the same apparent baseline for the displayed text.
        final float textScale = oldSize / textView.getTextSize();
        final float translationX = (1.0f - textScale) *
                (textView.getWidth() / 2.0f - textView.getPaddingEnd());
        final float translationY = (1.0f - textScale) *
                (textView.getHeight() / 2.0f - textView.getPaddingBottom());

        final AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(
                ObjectAnimator.ofFloat(textView, View.SCALE_X, textScale, 1.0f),
                ObjectAnimator.ofFloat(textView, View.SCALE_Y, textScale, 1.0f),
                ObjectAnimator.ofFloat(textView, View.TRANSLATION_X, translationX, 0.0f),
                ObjectAnimator.ofFloat(textView, View.TRANSLATION_Y, translationY, 0.0f));
        animatorSet.setDuration(getResources().getInteger(android.R.integer.config_mediumAnimTime));
        animatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorSet.start();
    }

    private void onEquals() {
        if (mCurrentState == CalculatorState.INPUT) {
            setState(CalculatorState.EVALUATE);
            mEvaluator.requireResult();
        }
    }

    private void onDelete() {
        // Delete works like backspace; remove the last character or operator from the expression.
        // Note that we handle keyboard delete exactly like the delete button.  For
        // example the delete button can be used to delete a character from an incomplete
        // function name typed on a physical keyboard.
        mEvaluator.cancelAll();
        // This should be impossible in RESULT state.
        setState(CalculatorState.INPUT);
        if (mUnprocessedChars != null) {
            int len = mUnprocessedChars.length();
            if (len > 0) {
                mUnprocessedChars = mUnprocessedChars.substring(0, len-1);
            } else {
                mEvaluator.getExpr().delete();
            }
        } else {
            mEvaluator.getExpr().delete();
        }
        redisplayAfterFormulaChange();
    }

    private void reveal(View sourceView, int colorRes, AnimatorListener listener) {
        final ViewGroupOverlay groupOverlay =
                (ViewGroupOverlay) getWindow().getDecorView().getOverlay();

        final Rect displayRect = new Rect();
        mDisplayView.getGlobalVisibleRect(displayRect);

        // Make reveal cover the display and status bar.
        final View revealView = new View(this);
        revealView.setBottom(displayRect.bottom);
        revealView.setLeft(displayRect.left);
        revealView.setRight(displayRect.right);
        revealView.setBackgroundColor(getResources().getColor(colorRes));
        groupOverlay.add(revealView);

        final int[] clearLocation = new int[2];
        sourceView.getLocationInWindow(clearLocation);
        clearLocation[0] += sourceView.getWidth() / 2;
        clearLocation[1] += sourceView.getHeight() / 2;

        final int revealCenterX = clearLocation[0] - revealView.getLeft();
        final int revealCenterY = clearLocation[1] - revealView.getTop();

        final double x1_2 = Math.pow(revealView.getLeft() - revealCenterX, 2);
        final double x2_2 = Math.pow(revealView.getRight() - revealCenterX, 2);
        final double y_2 = Math.pow(revealView.getTop() - revealCenterY, 2);
        final float revealRadius = (float) Math.max(Math.sqrt(x1_2 + y_2), Math.sqrt(x2_2 + y_2));

        final Animator revealAnimator =
                ViewAnimationUtils.createCircularReveal(revealView,
                        revealCenterX, revealCenterY, 0.0f, revealRadius);
        revealAnimator.setDuration(
                getResources().getInteger(android.R.integer.config_longAnimTime));
        revealAnimator.addListener(listener);

        final Animator alphaAnimator = ObjectAnimator.ofFloat(revealView, View.ALPHA, 0.0f);
        alphaAnimator.setDuration(
                getResources().getInteger(android.R.integer.config_mediumAnimTime));

        final AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.play(revealAnimator).before(alphaAnimator);
        animatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                groupOverlay.remove(revealView);
                mCurrentAnimator = null;
            }
        });

        mCurrentAnimator = animatorSet;
        animatorSet.start();
    }

    private void onClear() {
        if (mEvaluator.getExpr().isEmpty()) {
            return;
        }
        reveal(mCurrentButton, R.color.calculator_accent_color, new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mUnprocessedChars = null;
                mResult.clear();
                mEvaluator.clear();
                setState(CalculatorState.INPUT);
                redisplayFormula();
            }
        });
    }

    // Evaluation encountered en error.  Display the error.
    void onError(final int errorResourceId) {
        if (mCurrentState != CalculatorState.EVALUATE) {
            // Only animate error on evaluate.
            return;
        }

        setState(CalculatorState.ANIMATE);
        reveal(mCurrentButton, R.color.calculator_error_color, new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                setState(CalculatorState.ERROR);
                mResult.displayError(errorResourceId);
            }
        });
    }


    // Animate movement of result into the top formula slot.
    // Result window now remains translated in the top slot while the result is displayed.
    // (We convert it back to formula use only when the user provides new input.)
    // Historical note: In the Lollipop version, this invisibly and instantaeously moved
    // formula and result displays back at the end of the animation.  We no longer do that,
    // so that we can continue to properly support scrolling of the result.
    // We assume the result already contains the text to be expanded.
    private void onResult(boolean animate) {
        // Calculate the values needed to perform the scale and translation animations.
        // We now fix the character size in the display to avoid weird effects
        // when we scroll.
        // Display.xml is designed to ensure exactly a 3/2 ratio between the formula
        // slot and small result slot.
        final float resultScale = 1.5f;
        final float resultTranslationX = -mResult.getWidth() * (resultScale - 1)/2;
                // mFormulaEditText is aligned with mResult on the right.
                // When we enlarge it around its center, the right side
                // moves to the right.  This compensates.
        float resultTranslationY = -mResult.getHeight();
        // This is how much we want to move the bottom.
        // Now compensate for the fact that we're
        // simultaenously expanding it around its center by half its height
        resultTranslationY += mResult.getHeight() * (resultScale - 1)/2;
        final float formulaTranslationY = -mFormulaEditText.getBottom();

        // TODO: Reintroduce textColorAnimator?
        //       The initial and final colors seemed to be the same in L.
        //       With the new model, the result logically changes back to a formula
        //       only when we switch back to INPUT state, so it's unclear that animating
        //       a color change here makes sense.
        if (animate) {
            final AnimatorSet animatorSet = new AnimatorSet();
            animatorSet.playTogether(
                    ObjectAnimator.ofFloat(mResult, View.SCALE_X, resultScale),
                    ObjectAnimator.ofFloat(mResult, View.SCALE_Y, resultScale),
                    ObjectAnimator.ofFloat(mResult, View.TRANSLATION_X, resultTranslationX),
                    ObjectAnimator.ofFloat(mResult, View.TRANSLATION_Y, resultTranslationY),
                    ObjectAnimator.ofFloat(mFormulaEditText, View.TRANSLATION_Y,
                                           formulaTranslationY));
            animatorSet.setDuration(
                    getResources().getInteger(android.R.integer.config_longAnimTime));
            animatorSet.setInterpolator(new AccelerateDecelerateInterpolator());
            animatorSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    // Result should already be displayed; no need to do anything.
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    setState(CalculatorState.RESULT);
                    mCurrentAnimator = null;
                }
            });

            mCurrentAnimator = animatorSet;
            animatorSet.start();
        } else /* No animation desired; get there fast, e.g. when restarting */ {
            mResult.setScaleX(resultScale);
            mResult.setScaleY(resultScale);
            mResult.setTranslationX(resultTranslationX);
            mResult.setTranslationY(resultTranslationY);
            mFormulaEditText.setTranslationY(formulaTranslationY);
            setState(CalculatorState.RESULT);
        }
    }

    // Restore positions of the formula and result displays back to their original,
    // pre-animation state.
    private void restoreDisplayPositions() {
        // Clear result.
        mResult.setText("");
        // Reset all of the values modified during the animation.
        mResult.setScaleX(1.0f);
        mResult.setScaleY(1.0f);
        mResult.setTranslationX(0.0f);
        mResult.setTranslationY(0.0f);
        mFormulaEditText.setTranslationY(0.0f);

        mFormulaEditText.requestFocus();
     }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.overflow, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (mCurrentState != CalculatorState.RESULT) {
            menu.findItem(R.id.menu_fraction).setEnabled(false);
            menu.findItem(R.id.menu_leading).setEnabled(false);
        } else if (mEvaluator.getRational() == null) {
            menu.findItem(R.id.menu_fraction).setEnabled(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_help:
                displayHelpMessage();
                return true;
            case R.id.menu_about:
                displayAboutPage();
                return true;
            case R.id.menu_fraction:
                displayFraction();
                return true;
            case R.id.menu_leading:
                displayFull();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void displayMessage(String s) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(s)
               .setNegativeButton(R.string.dismiss,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) { }
                    })
               .show();
    }

    private void displayHelpMessage() {
        Resources res = getResources();
        String msg = res.getString(R.string.help_message);
        if (mPadViewPager != null) {
            msg += res.getString(R.string.help_pager);
        }
        displayMessage(msg);
    }

    private void displayFraction() {
        BoundedRational result = mEvaluator.getRational();
        displayMessage(KeyMaps.translateResult(result.toNiceString()));
    }

    // Display full result to currently evaluated precision
    private void displayFull() {
        Resources res = getResources();
        String msg = mResult.getFullText() + " ";
        if (mResult.fullTextIsExact()) {
            msg += res.getString(R.string.exact);
        } else {
            msg += res.getString(R.string.approximate);
        }
        displayMessage(msg);
    }

    private void displayAboutPage() {
        WebView wv = new WebView(this);
        wv.loadUrl("file:///android_asset/about.txt");
        new AlertDialog.Builder(this)
                .setView(wv)
                .setNegativeButton(R.string.dismiss,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int which) { }
                    })
                .show();
    }

    // Add input characters to the end of the expression by mapping them to
    // the appropriate button pushes when possible.  Leftover characters
    // are added to mUnprocessedChars, which is presumed to immediately
    // precede the newly added characters.
    private void addChars(String moreChars) {
        if (mUnprocessedChars != null) {
            moreChars = mUnprocessedChars + moreChars;
        }
        int current = 0;
        int len = moreChars.length();
        while (current < len) {
            char c = moreChars.charAt(current);
            int k = KeyMaps.keyForChar(c);
            if (k != View.NO_ID) {
                mCurrentButton = findViewById(k);
                addKeyToExpr(k);
                if (Character.isSurrogate(c)) {
                    current += 2;
                } else {
                    ++current;
                }
                continue;
            }
            int f = KeyMaps.funForString(moreChars, current);
            if (f != View.NO_ID) {
                mCurrentButton = findViewById(f);
                addKeyToExpr(f);
                if (f == R.id.op_sqrt) {
                    // Square root entered as function; don't lose the parenthesis.
                    addKeyToExpr(R.id.lparen);
                }
                current = moreChars.indexOf('(', current) + 1;
                continue;
            }
            // There are characters left, but we can't convert them to button presses.
            mUnprocessedChars = moreChars.substring(current);
            redisplayAfterFormulaChange();
            return;
        }
        mUnprocessedChars = null;
        redisplayAfterFormulaChange();
        return;
    }

    @Override
    public boolean paste(Uri uri) {
        if (mEvaluator.isLastSaved(uri)) {
            if (mCurrentState == CalculatorState.ERROR
                || mCurrentState == CalculatorState.RESULT) {
                setState(CalculatorState.INPUT);
                mEvaluator.clear();
            }
            mEvaluator.addSaved();
            redisplayAfterFormulaChange();
            return true;
        }
        return false;
    }

    @Override
    public void paste(String s) {
        addChars(s);
    }

}
