package com.mooo.hairyone.td5tester.fragments;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.mooo.hairyone.td5tester.Log4jHelper;
import com.mooo.hairyone.td5tester.R;
import com.mooo.hairyone.td5tester.Settings;
import com.mooo.hairyone.td5tester.Vehicle;

import org.apache.log4j.Logger;

import java.util.Locale;

/** Lets the thresholds behind every gauge colour be changed and put back. */
public class SettingsFragment extends BaseFragment {

    Logger log = Log4jHelper.getLogger(this.getClass());

    private LinearLayout list;

    public SettingsFragment() { /* Required empty public constructor */ }

    @Override protected boolean listensToEvents() { return false; }

    @Override protected int getFragmentLayout() { return R.layout.settings_fragment; }

    private static String format(float value) {
        if (value == Math.rint(value)) return String.valueOf((int) value);
        return String.format(Locale.US, "%.2f", value);
    }

    @Override protected void bindViews(View view) {
        list = view.findViewById(R.id.settingsList);
        view.findViewById(R.id.btResetSettings).setOnClickListener(v ->
                new AlertDialog.Builder(requireContext(), R.style.InfoDialog)
                        .setTitle(R.string.set_reset_title)
                        .setMessage(R.string.set_reset_body)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.set_reset_confirm, (d, w) -> {
                            Settings.resetAll(requireContext());
                            // The tank goes back to the chosen model's, not a generic one.
                            int model = Vehicle.model(requireContext());
                            if (model >= 0) Vehicle.setModel(requireContext(), model);
                            build();
                        })
                        .show());
        build();
    }

    /**
     * One framed block per category, a title above it and a gap below, the
     * rows inside separated by a hairline.
     */
    private void build() {
        list.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        String section = null;
        LinearLayout block = null;

        for (Settings.Entry entry : Settings.ENTRIES) {
            if (!entry.section.equals(section)) {
                section = entry.section;
                TextView header = new TextView(requireContext());
                header.setText(section.toUpperCase(Locale.FRENCH));
                header.setTextSize(10.5f);
                header.setLetterSpacing(0.24f);
                header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
                header.setTextColor(getResources().getColor(R.color.labelSecondary));
                header.setPadding((int) (4 * density), (int) (block == null ? 6 * density : 20 * density), 0, (int) (8 * density));
                list.addView(header);
                block = new LinearLayout(requireContext());
                block.setOrientation(LinearLayout.VERTICAL);
                block.setBackgroundResource(R.drawable.block);
                list.addView(block, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                if (section.equals(Settings.VEHICLE)) block.addView(modelRow(density));
            }
            if (block.getChildCount() > 0) block.addView(separator(density));
            block.addView(row(entry, density));
        }
    }

    private View separator(float density) {
        View line = new View(requireContext());
        line.setBackgroundColor(getResources().getColor(R.color.hairline));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int) density));
        lp.setMarginStart((int) (20 * density));
        lp.setMarginEnd((int) (20 * density));
        line.setLayoutParams(lp);
        return line;
    }

    /** The model, chosen from a list; it fills in the tank capacity below it. */
    private View modelRow(float density) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(0);
        row.setPadding((int) (20 * density), (int) (14 * density),
                (int) (20 * density), (int) (14 * density));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = 0;
        row.setLayoutParams(rp);

        TextView label = new TextView(requireContext());
        label.setText(R.string.vehicle_model);
        label.setTextSize(15f);
        label.setTextColor(getResources().getColor(R.color.ink));
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        String name = Vehicle.name(requireContext());
        TextView value = new TextView(requireContext());
        value.setText(name == null ? getString(R.string.vehicle_none) + "  ›" : name + "  ›");
        value.setTextSize(17f);
        value.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        value.setTextColor(getResources().getColor(name == null ? R.color.stateHigh : R.color.ink));
        row.addView(value);

        row.setOnClickListener(v -> Vehicle.choose(requireContext(), this::build));
        return row;
    }

    private View row(Settings.Entry entry, float density) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(0);
        row.setPadding((int) (20 * density), (int) (6 * density),
                (int) (20 * density), (int) (6 * density));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = 0;
        row.setLayoutParams(rp);

        TextView label = new TextView(requireContext());
        label.setText(entry.label);
        label.setTextSize(15f);
        label.setTextColor(getResources().getColor(R.color.ink));
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        EditText field = new EditText(requireContext());
        field.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        field.setText(format(Settings.get(requireContext(), entry.key)));
        field.setTextSize(18f);
        field.setGravity(Gravity.END);
        field.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        field.setTextColor(getResources().getColor(R.color.ink));
        field.setBackground(null);
        field.setLayoutParams(new LinearLayout.LayoutParams((int) (86 * density),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(field);

        TextView unit = new TextView(requireContext());
        unit.setText(entry.unit);
        unit.setTextSize(12f);
        unit.setTextColor(getResources().getColor(R.color.labelSecondary));
        unit.setPadding((int) (8 * density), 0, 0, 0);
        unit.setLayoutParams(new LinearLayout.LayoutParams((int) (54 * density),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(unit);

        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                String text = s.toString().replace(',', '.').trim();
                // An empty or half-typed field is left alone rather than saved as zero.
                if (text.isEmpty() || text.equals("-") || text.equals(".")) return;
                try {
                    Settings.set(requireContext(), entry.key, Float.parseFloat(text));
                } catch (NumberFormatException ignored) { }
            }
        });

        return row;
    }
}
