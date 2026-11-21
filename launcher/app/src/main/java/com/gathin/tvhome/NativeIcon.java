package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/** The app's own icon, drawn the way the launcher mask does: full-bleed, not a glyph on a plate. */
final class NativeIcon {
    private NativeIcon() { }

    static Drawable of(Context ctx, Drawable src) {
        if (src == null) return null;
        int size = Math.round(64f * ctx.getResources().getDisplayMetrics().density * 2f);
        try {
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            if (src instanceof AdaptiveIconDrawable) {
                AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) src;
                float scale = 1f + 2f * AdaptiveIconDrawable.getExtraInsetFraction();
                int layer = Math.round(size * scale);
                int offset = (size - layer) / 2;
                draw(adaptive.getBackground(), canvas, offset, layer);
                draw(adaptive.getForeground(), canvas, offset, layer);
            } else {
                draw(src, canvas, 0, size);
            }
            return new BitmapDrawable(ctx.getResources(), bitmap);
        } catch (RuntimeException ignored) {
            return src;
        }
    }

    private static void draw(Drawable layer, Canvas canvas, int offset, int size) {
        if (layer == null) return;
        Drawable copy = layer.getConstantState() != null ? layer.getConstantState().newDrawable().mutate() : layer;
        copy.setBounds(offset, offset, offset + size, offset + size);
        copy.draw(canvas);
    }
}
