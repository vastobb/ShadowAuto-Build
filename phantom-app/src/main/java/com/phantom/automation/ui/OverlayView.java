package com.phantom.automation.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Draws OCR/target bounding boxes over the screenshot (display-local coords). */
public final class OverlayView extends View {
    public static final class Box {
        final float left;
        final float top;
        final float right;
        final float bottom;
        final boolean highlight;

        Box(float left, float top, float right, float bottom, boolean highlight) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.highlight = highlight;
        }
    }

    private final Paint boxPaint = new Paint();
    private final Paint hitPaint = new Paint();
    private final List<Box> boxes = new ArrayList<>();
    private int contentWidth = 1;
    private int contentHeight = 1;

    public OverlayView(Context context) {
        super(context);
        init();
    }

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3);
        boxPaint.setColor(0xFF8B7CFF);
        hitPaint.setStyle(Paint.Style.STROKE);
        hitPaint.setStrokeWidth(5);
        hitPaint.setColor(0xFF4ADE80);
    }

    public void setBoxes(List<Box> next, int contentWidth, int contentHeight) {
        boxes.clear();
        if (next != null) {
            boxes.addAll(next);
        }
        this.contentWidth = Math.max(1, contentWidth);
        this.contentHeight = Math.max(1, contentHeight);
        invalidate();
    }

    public void clear() {
        boxes.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scaleX = getWidth() / (float) contentWidth;
        float scaleY = getHeight() / (float) contentHeight;
        for (Box box : boxes) {
            canvas.drawRect(box.left * scaleX, box.top * scaleY,
                    box.right * scaleX, box.bottom * scaleY,
                    box.highlight ? hitPaint : boxPaint);
        }
    }
}
