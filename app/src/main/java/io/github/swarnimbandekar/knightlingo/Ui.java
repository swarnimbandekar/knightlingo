package io.github.swarnimbandekar.knightlingo;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Duolingo dark-mode tokens: flat surfaces, 2dp borders and pressable "3D" lips. */
final class Ui {
    static final int BG = Color.parseColor("#131f24");
    static final int CARD = BG;
    static final int LINE = Color.parseColor("#37464f");
    static final int TEXT = Color.WHITE;
    /** The spec's muted grey; too dim for text, so only used for disabled and subtle parts. */
    static final int SUBTLE = Color.parseColor("#52656d");
    /** Readable secondary text (about 6.5:1 on BG). */
    static final int MUTED = Color.parseColor("#93a5ad");
    static final int GREEN = Color.parseColor("#58cc02");
    static final int GREEN_LIP = Color.parseColor("#58a700");
    static final int BLUE = Color.parseColor("#1cb0f6");
    static final int BLUE_LIP = Color.parseColor("#1899d6");
    static final int YELLOW = Color.parseColor("#ffc800");
    static final int RED = Color.parseColor("#ff4b4b");
    static final int CAPTURE = YELLOW;
    /**
     * Rim of the overlay Tip button. TipService looks for this exact colour in captured frames to
     * detect full-screen sharing, so it must never be a colour Duolingo itself draws.
     */
    static final int PROBE = Color.rgb(176, 237, 122);

    private static final int LIP = 4, BORDER = 2;

    private Ui() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable background(int color, float radius, Context context) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(context, radius));
        return result;
    }

    /** Feather is proprietary; heavy system sans keeps the bold, friendly hierarchy. */
    static Typeface weight(int weight) {
        return Typeface.create(Typeface.SANS_SERIF, weight, false);
    }

    static TextView text(Context context, String text, int size, int color, boolean heading) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(context, 3), 1f);
        view.setTypeface(weight(heading ? 800 : 700));
        if (heading) view.setLetterSpacing(.02f);
        return view;
    }

    /** Uppercase eyebrow label above a section. */
    static TextView label(Context context, String text, int color) {
        TextView view = text(context, text.toUpperCase(java.util.Locale.ROOT), 13, color, true);
        view.setLetterSpacing(.08f);
        return view;
    }

    /** A flat face sitting on a darker lip; pressing sinks the face onto the lip. */
    static Drawable raised(Context context, int face, int lip, int border, float radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, layers(context, LINE, LINE, 0, radius, false));
        states.addState(new int[]{android.R.attr.state_pressed}, layers(context, face, lip, border, radius, true));
        states.addState(new int[]{}, layers(context, face, lip, border, radius, false));
        return states;
    }

    private static LayerDrawable layers(Context context, int face, int lip, int border, float radius, boolean pressed) {
        GradientDrawable base = background(lip, radius, context);
        GradientDrawable top = background(face, radius, context);
        if (border != 0) top.setStroke(dp(context, BORDER), border);
        LayerDrawable result = new LayerDrawable(new Drawable[]{base, top});
        int lipHeight = dp(context, LIP);
        if (pressed) {
            result.setLayerInset(0, 0, lipHeight, 0, 0);
            result.setLayerInset(1, 0, lipHeight, 0, 0);
        } else {
            result.setLayerInset(1, 0, 0, 0, lipHeight);
        }
        return result;
    }

    /** Bordered card with the same bottom lip as buttons. */
    static Drawable card(Context context, float radius) {
        return layers(context, CARD, LINE, LINE, radius, false);
    }

    static Drawable card(Context context) { return card(context, 16); }

    /** Fully rounded pill such as a badge. */
    static TextView chip(Context context, String text, int color) {
        TextView view = text(context, text, 12, color, true);
        view.setLetterSpacing(.06f);
        view.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 6));
        GradientDrawable pill = background((color & 0xffffff) | 0x26000000, 9999, context);
        pill.setStroke(dp(context, BORDER), (color & 0xffffff) | 0x80000000);
        view.setBackground(pill);
        return view;
    }

    /** The "Big" button: 16dp radius, uppercase extra-bold label, 4dp lip and a bouncy press. */
    static Button button(Context context, String text, boolean primary) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextSize(15);
        button.setAllCaps(true);
        button.setLetterSpacing(.05f);
        button.setTypeface(weight(800));
        setStyle(button, primary ? GREEN : BG, primary ? GREEN_LIP : LINE, primary ? 0 : LINE,
                primary ? TEXT : BLUE);
        button.setMinHeight(dp(context, 56));
        button.setMinimumHeight(dp(context, 56));
        button.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8 + LIP));
        button.setStateListAnimator(bounce());
        return button;
    }

    static void setStyle(Button button, int face, int lip, int border, int textColor) {
        setStyle(button, face, lip, border, textColor, 16);
    }

    /** A plain circle without a lip: flat face, optional 2dp rim, slightly darker while pressed. */
    static void setFlatRound(Button button, int face, int border, int textColor) {
        Context context = button.getContext();
        StateListDrawable states = new StateListDrawable();
        for (boolean pressed : new boolean[]{true, false}) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(pressed ? blend(face, Color.BLACK, .2f) : face);
            if (border != 0) circle.setStroke(dp(context, BORDER), border);
            states.addState(pressed ? new int[]{android.R.attr.state_pressed} : new int[]{}, circle);
        }
        button.setBackground(states);
        button.setTextColor(textColor);
        button.setStateListAnimator(null);
    }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }

    private static void setStyle(Button button, int face, int lip, int border, int textColor, float radius) {
        button.setBackground(raised(button.getContext(), face, lip, border, radius));
        button.setTextColor(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{SUBTLE, textColor}));
    }

    private static StateListAnimator bounce() {
        StateListAnimator result = new StateListAnimator();
        result.addState(new int[]{android.R.attr.state_pressed}, scale(.96f, 70, null));
        result.addState(new int[]{}, scale(1f, 260, new OvershootInterpolator(4f)));
        return result;
    }

    private static AnimatorSet scale(float to, long millis, android.animation.TimeInterpolator easing) {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(ObjectAnimator.ofFloat(null, View.SCALE_X, to), ObjectAnimator.ofFloat(null, View.SCALE_Y, to));
        set.setDuration(millis);
        if (easing != null) set.setInterpolator(easing);
        return set;
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(parent.getContext(), topMargin);
        parent.addView(child, params);
    }

    static void insets(Activity activity, View root, int horizontal, int vertical) {
        activity.getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets system = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(activity, horizontal) + system.left,
                    dp(activity, vertical) + system.top,
                    dp(activity, horizontal) + system.right,
                    dp(activity, vertical) + system.bottom);
            return insets;
        });
    }
}
