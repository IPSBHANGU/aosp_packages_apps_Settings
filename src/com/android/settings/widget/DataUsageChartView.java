/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.widget;

import android.content.Context;
import android.content.res.Resources;
import android.net.NetworkPolicy;
import android.net.NetworkStatsHistory;
import android.os.Handler;
import android.os.Message;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.android.settings.R;
import com.android.settings.widget.ChartSweepView.OnSweepListener;

/**
 * Specific {@link ChartView} that displays {@link ChartNetworkSeriesView} along
 * with {@link ChartSweepView} for inspection ranges and warning/limits.
 */
public class DataUsageChartView extends ChartView {

    private static final long KB_IN_BYTES = 1024;
    private static final long MB_IN_BYTES = KB_IN_BYTES * 1024;
    private static final long GB_IN_BYTES = MB_IN_BYTES * 1024;

    private static final int MSG_UPDATE_AXIS = 100;
    private static final long DELAY_MILLIS = 250;

    private ChartGridView mGrid;
    private ChartNetworkSeriesView mSeries;
    private ChartNetworkSeriesView mDetailSeries;

    private NetworkStatsHistory mHistory;

    private ChartSweepView mSweepLeft;
    private ChartSweepView mSweepRight;
    private ChartSweepView mSweepWarning;
    private ChartSweepView mSweepLimit;

    private Handler mHandler;

    /** Current maximum value of {@link #mVert}. */
    private long mVertMax;

    public interface DataUsageChartListener {
        public void onInspectRangeChanged();
        public void onWarningChanged();
        public void onLimitChanged();
    }

    private DataUsageChartListener mListener;

    public DataUsageChartView(Context context) {
        this(context, null, 0);
    }

    public DataUsageChartView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DataUsageChartView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(new TimeAxis(), new InvertedChartAxis(new DataAxis()));

        mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                final ChartSweepView sweep = (ChartSweepView) msg.obj;
                updateVertAxisBounds(sweep);
                updateEstimateVisible();

                // we keep dispatching repeating updates until sweep is dropped
                sendUpdateAxisDelayed(sweep, true);
            }
        };
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mGrid = (ChartGridView) findViewById(R.id.grid);
        mSeries = (ChartNetworkSeriesView) findViewById(R.id.series);
        mDetailSeries = (ChartNetworkSeriesView) findViewById(R.id.detail_series);
        mDetailSeries.setVisibility(View.GONE);

        mSweepLeft = (ChartSweepView) findViewById(R.id.sweep_left);
        mSweepRight = (ChartSweepView) findViewById(R.id.sweep_right);
        mSweepLimit = (ChartSweepView) findViewById(R.id.sweep_limit);
        mSweepWarning = (ChartSweepView) findViewById(R.id.sweep_warning);

        // prevent sweeps from crossing each other
        mSweepLeft.setValidRangeDynamic(null, mSweepRight);
        mSweepRight.setValidRangeDynamic(mSweepLeft, null);
        mSweepWarning.setValidRangeDynamic(null, mSweepLimit);
        mSweepLimit.setValidRangeDynamic(mSweepWarning, null);

        mSweepLeft.addOnSweepListener(mHorizListener);
        mSweepRight.addOnSweepListener(mHorizListener);
        mSweepWarning.addOnSweepListener(mVertListener);
        mSweepLimit.addOnSweepListener(mVertListener);

        // tell everyone about our axis
        mGrid.init(mHoriz, mVert);
        mSeries.init(mHoriz, mVert);
        mDetailSeries.init(mHoriz, mVert);
        mSweepLeft.init(mHoriz);
        mSweepRight.init(mHoriz);
        mSweepWarning.init(mVert);
        mSweepLimit.init(mVert);

        setActivated(false);
    }

    public void setListener(DataUsageChartListener listener) {
        mListener = listener;
    }

    public void bindNetworkStats(NetworkStatsHistory stats) {
        mSeries.bindNetworkStats(stats);
        mHistory = stats;
        updateVertAxisBounds(null);
        updateEstimateVisible();
        updatePrimaryRange();
        requestLayout();
    }

    public void bindDetailNetworkStats(NetworkStatsHistory stats) {
        mDetailSeries.bindNetworkStats(stats);
        mDetailSeries.setVisibility(stats != null ? View.VISIBLE : View.GONE);
        if (mHistory != null) {
            mDetailSeries.setEndTime(mHistory.getEnd());
        }
        updateVertAxisBounds(null);
        updateEstimateVisible();
        updatePrimaryRange();
        requestLayout();
    }

    public void bindNetworkPolicy(NetworkPolicy policy) {
        if (policy == null) {
            mSweepLimit.setVisibility(View.INVISIBLE);
            mSweepLimit.setValue(-1);
            mSweepWarning.setVisibility(View.INVISIBLE);
            mSweepWarning.setValue(-1);
            return;
        }

        if (policy.limitBytes != NetworkPolicy.LIMIT_DISABLED) {
            mSweepLimit.setVisibility(View.VISIBLE);
            mSweepLimit.setEnabled(true);
            mSweepLimit.setValue(policy.limitBytes);
        } else {
            mSweepLimit.setVisibility(View.VISIBLE);
            mSweepLimit.setEnabled(false);
            mSweepLimit.setValue(-1);
        }

        if (policy.warningBytes != NetworkPolicy.WARNING_DISABLED) {
            mSweepWarning.setVisibility(View.VISIBLE);
            mSweepWarning.setValue(policy.warningBytes);
        } else {
            mSweepWarning.setVisibility(View.INVISIBLE);
            mSweepWarning.setValue(-1);
        }

        updateVertAxisBounds(null);
        requestLayout();
    }

    /**
     * Update {@link #mVert} to both show data from {@link NetworkStatsHistory}
     * and controls from {@link NetworkPolicy}.
     */
    private void updateVertAxisBounds(ChartSweepView activeSweep) {
        final long max = mVertMax;
        final long newMax;
        if (activeSweep != null) {
            final int adjustAxis = activeSweep.shouldAdjustAxis();
            if (adjustAxis > 0) {
                // hovering around upper edge, grow axis
                newMax = max * 11 / 10;
            } else if (adjustAxis < 0) {
                // hovering around lower edge, shrink axis
                newMax = max * 9 / 10;
            } else {
                newMax = max;
            }

        } else {
            // try showing all known data and policy
            final long maxSweep = Math.max(mSweepWarning.getValue(), mSweepLimit.getValue());
            final long maxVisible = Math.max(mSeries.getMaxVisible(), maxSweep) * 12 / 10;
            newMax = Math.max(maxVisible, 2 * GB_IN_BYTES);
        }

        // only invalidate when vertMax actually changed
        if (newMax != mVertMax) {
            mVertMax = newMax;

            mVert.setBounds(0L, newMax);
            mSweepWarning.setValidRange(0L, newMax);
            mSweepLimit.setValidRange(0L, newMax);

            mSeries.generatePath();
            mDetailSeries.generatePath();

            mGrid.invalidate();

            // since we just changed axis, make sweep recalculate its value
            if (activeSweep != null) {
                activeSweep.updateValueFromPosition();
            }
        }
    }

    /**
     * Control {@link ChartNetworkSeriesView#setEstimateVisible(boolean)} based
     * on how close estimate comes to {@link #mSweepWarning}.
     */
    private void updateEstimateVisible() {
        final long maxEstimate = mSeries.getMaxEstimate();

        // show estimate when near warning/limit
        long interestLine = Long.MAX_VALUE;
        if (mSweepWarning.isEnabled()) {
            interestLine = mSweepWarning.getValue();
        } else if (mSweepLimit.isEnabled()) {
            interestLine = mSweepLimit.getValue();
        }

        final boolean estimateVisible = (maxEstimate >= interestLine * 7 / 10);
        mSeries.setEstimateVisible(estimateVisible);
    }

    private OnSweepListener mHorizListener = new OnSweepListener() {
        public void onSweep(ChartSweepView sweep, boolean sweepDone) {
            updatePrimaryRange();

            // update detail list only when done sweeping
            if (sweepDone && mListener != null) {
                mListener.onInspectRangeChanged();
            }
        }
    };

    private void sendUpdateAxisDelayed(ChartSweepView sweep, boolean force) {
        if (force || !mHandler.hasMessages(MSG_UPDATE_AXIS, sweep)) {
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(MSG_UPDATE_AXIS, sweep), DELAY_MILLIS);
        }
    }

    private void clearUpdateAxisDelayed(ChartSweepView sweep) {
        mHandler.removeMessages(MSG_UPDATE_AXIS, sweep);
    }

    private OnSweepListener mVertListener = new OnSweepListener() {
        public void onSweep(ChartSweepView sweep, boolean sweepDone) {
            if (sweepDone) {
                clearUpdateAxisDelayed(sweep);
                updateEstimateVisible();

                if (sweep == mSweepWarning && mListener != null) {
                    mListener.onWarningChanged();
                } else if (sweep == mSweepLimit && mListener != null) {
                    mListener.onLimitChanged();
                }
            } else {
                // while moving, kick off delayed grow/shrink axis updates
                sendUpdateAxisDelayed(sweep, false);
            }
        }
    };

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (isActivated()) return false;
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                return true;
            }
            case MotionEvent.ACTION_UP: {
                setActivated(true);
                return true;
            }
            default: {
                return false;
            }
        }
    }

    public long getInspectStart() {
        return mSweepLeft.getValue();
    }

    public long getInspectEnd() {
        return mSweepRight.getValue();
    }

    public long getWarningBytes() {
        return mSweepWarning.getValue();
    }

    public long getLimitBytes() {
        return mSweepLimit.getValue();
    }

    private long getStatsStart() {
        return mHistory != null ? mHistory.getStart() : Long.MIN_VALUE;
    }

    private long getStatsEnd() {
        return mHistory != null ? mHistory.getEnd() : Long.MAX_VALUE;
    }

    /**
     * Set the exact time range that should be displayed, updating how
     * {@link ChartNetworkSeriesView} paints. Moves inspection ranges to be the
     * last "week" of available data, without triggering listener events.
     */
    public void setVisibleRange(long visibleStart, long visibleEnd) {
        mHoriz.setBounds(visibleStart, visibleEnd);
        mGrid.setBounds(visibleStart, visibleEnd);

        final long validStart = Math.max(visibleStart, getStatsStart());
        final long validEnd = Math.min(visibleEnd, getStatsEnd());

        // prevent time sweeps from leaving valid data
        mSweepLeft.setValidRange(validStart, validEnd);
        mSweepRight.setValidRange(validStart, validEnd);

        // default sweeps to last week of data
        final long halfRange = (visibleEnd + visibleStart) / 2;
        final long sweepMax = validEnd;
        final long sweepMin = Math.max(visibleStart, (sweepMax - DateUtils.WEEK_IN_MILLIS));

        mSweepLeft.setValue(sweepMin);
        mSweepRight.setValue(sweepMax);

        requestLayout();
        mSeries.generatePath();

        updateVertAxisBounds(null);
        updateEstimateVisible();
        updatePrimaryRange();
    }

    private void updatePrimaryRange() {
        final long left = mSweepLeft.getValue();
        final long right = mSweepRight.getValue();

        // prefer showing primary range on detail series, when available
        if (mDetailSeries.getVisibility() == View.VISIBLE) {
            mDetailSeries.setPrimaryRange(left, right);
            mSeries.setPrimaryRange(0, 0);
        } else {
            mSeries.setPrimaryRange(left, right);
        }
    }

    public static class TimeAxis implements ChartAxis {
        private static final long TICK_INTERVAL = DateUtils.DAY_IN_MILLIS * 7;

        private long mMin;
        private long mMax;
        private float mSize;

        public TimeAxis() {
            final long currentTime = System.currentTimeMillis();
            setBounds(currentTime - DateUtils.DAY_IN_MILLIS * 30, currentTime);
        }

        /** {@inheritDoc} */
        public void setBounds(long min, long max) {
            mMin = min;
            mMax = max;
        }

        /** {@inheritDoc} */
        public void setSize(float size) {
            this.mSize = size;
        }

        /** {@inheritDoc} */
        public float convertToPoint(long value) {
            return (mSize * (value - mMin)) / (mMax - mMin);
        }

        /** {@inheritDoc} */
        public long convertToValue(float point) {
            return (long) (mMin + ((point * (mMax - mMin)) / mSize));
        }

        /** {@inheritDoc} */
        public void buildLabel(Resources res, SpannableStringBuilder builder, long value) {
            // TODO: convert to better string
            builder.replace(0, builder.length(), Long.toString(value));
        }

        /** {@inheritDoc} */
        public float[] getTickPoints() {
            // tick mark for every week
            final int tickCount = (int) ((mMax - mMin) / TICK_INTERVAL);
            final float[] tickPoints = new float[tickCount];
            for (int i = 0; i < tickCount; i++) {
                tickPoints[i] = convertToPoint(mMax - (TICK_INTERVAL * i));
            }
            return tickPoints;
        }

        /** {@inheritDoc} */
        public int shouldAdjustAxis(long value) {
            // time axis never adjusts
            return 0;
        }
    }

    public static class DataAxis implements ChartAxis {
        private long mMin;
        private long mMax;
        private float mSize;

        /** {@inheritDoc} */
        public void setBounds(long min, long max) {
            mMin = min;
            mMax = max;
        }

        /** {@inheritDoc} */
        public void setSize(float size) {
            mSize = size;
        }

        /** {@inheritDoc} */
        public float convertToPoint(long value) {
            // derived polynomial fit to make lower values more visible
            final double normalized = ((double) value - mMin) / (mMax - mMin);
            final double fraction = Math.pow(
                    10, 0.36884343106175121463 * Math.log10(normalized) + -0.04328199452018252624);
            return (float) (fraction * mSize);
        }

        /** {@inheritDoc} */
        public long convertToValue(float point) {
            final double normalized = point / mSize;
            final double fraction = 1.3102228476089056629
                    * Math.pow(normalized, 2.7111774693164631640);
            return (long) (mMin + (fraction * (mMax - mMin)));
        }

        private static final Object sSpanSize = new Object();
        private static final Object sSpanUnit = new Object();

        /** {@inheritDoc} */
        public void buildLabel(Resources res, SpannableStringBuilder builder, long value) {

            float result = value;
            final CharSequence unit;
            if (result <= 100 * MB_IN_BYTES) {
                unit = res.getText(com.android.internal.R.string.megabyteShort);
                result /= MB_IN_BYTES;
            } else {
                unit = res.getText(com.android.internal.R.string.gigabyteShort);
                result /= GB_IN_BYTES;
            }

            final CharSequence size;
            if (result < 10) {
                size = String.format("%.1f", result);
            } else {
                size = String.format("%.0f", result);
            }

            final int[] sizeBounds = findOrCreateSpan(builder, sSpanSize, "^1");
            builder.replace(sizeBounds[0], sizeBounds[1], size);
            final int[] unitBounds = findOrCreateSpan(builder, sSpanUnit, "^2");
            builder.replace(unitBounds[0], unitBounds[1], unit);
        }

        /** {@inheritDoc} */
        public float[] getTickPoints() {
            final long range = mMax - mMin;
            final long tickJump = 256 * MB_IN_BYTES;

            final int tickCount = (int) (range / tickJump);
            final float[] tickPoints = new float[tickCount];
            long value = mMin;
            for (int i = 0; i < tickPoints.length; i++) {
                tickPoints[i] = convertToPoint(value);
                value += tickJump;
            }

            return tickPoints;
        }

        /** {@inheritDoc} */
        public int shouldAdjustAxis(long value) {
            final float point = convertToPoint(value);
            if (point < mSize * 0.1) {
                return -1;
            } else if (point > mSize * 0.85) {
                return 1;
            } else {
                return 0;
            }
        }
    }

    private static int[] findOrCreateSpan(
            SpannableStringBuilder builder, Object key, CharSequence bootstrap) {
        int start = builder.getSpanStart(key);
        int end = builder.getSpanEnd(key);
        if (start == -1) {
            start = TextUtils.indexOf(builder, bootstrap);
            end = start + bootstrap.length();
            builder.setSpan(key, start, end, Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        }
        return new int[] { start, end };
    }

}
