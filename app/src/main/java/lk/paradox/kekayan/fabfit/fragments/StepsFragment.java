package lk.paradox.kekayan.fabfit.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import org.eazegraph.lib.charts.PieChart;
import org.eazegraph.lib.models.PieModel;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

import lk.paradox.kekayan.fabfit.BuildConfig;
import lk.paradox.kekayan.fabfit.R;
import lk.paradox.kekayan.fabfit.db.Database;
import lk.paradox.kekayan.fabfit.helpers.Logger;
import lk.paradox.kekayan.fabfit.helpers.Util;


public class StepsFragment extends Fragment implements SensorEventListener {

    public final static NumberFormat formatter = NumberFormat.getInstance(Locale.getDefault());
    // from https://github.com/bagilevi/android-pedometer/blob/master/src/name/bagi/levente/pedometer/CaloriesNotifier.java
    private static double METRIC_RUNNING_FACTOR = 1.02784823;
    private static double METRIC_WALKING_FACTOR = 0.708;
    private static double METRIC_AVG_FACTOR = (METRIC_RUNNING_FACTOR + METRIC_WALKING_FACTOR) / 2;
    ImageView footImage;
    private TextView stepsView, totalView, averageView, caloriesView;
    private PieModel sliceGoal, sliceCurrent;
    private PieChart pg;
    private int todayOffset, total_start, goal, since_boot, total_days;
    private boolean showSteps = true;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        final View v = inflater.inflate(R.layout.fragment_steps, null);

        footImage = v.findViewById(R.id.stepsimageView);

        stepsView = v.findViewById(R.id.steps);
        totalView = v.findViewById(R.id.total);
        averageView = v.findViewById(R.id.average);
        caloriesView = v.findViewById(R.id.tv_calories);

        pg = v.findViewById(R.id.graph);
        pg.setInnerPadding(88.f);
        // slice for the steps taken today
        sliceCurrent = new PieModel("", 0, Color.parseColor("#99CC00"));
        pg.addPieSlice(sliceCurrent);

        // slice for the "missing" steps until reaching the goal
        sliceGoal = new PieModel("", SettingsFragment.DEFAULT_GOAL, Color.parseColor("#CC0000"));
        pg.addPieSlice(sliceGoal);

        pg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View view) {
                showSteps = !showSteps;
                stepsDistanceChanged();
            }
        });
        pg.startAnimation();
        return v;
    }

    @Override
    public void onResume() {
        super.onResume();


        Database db = Database.getInstance(getActivity());

        if (BuildConfig.DEBUG) db.logState();
        // read todays offset
        todayOffset = db.getSteps(Util.getToday());

        SharedPreferences prefs =
                Objects.requireNonNull(getActivity()).getSharedPreferences("FabFit", Context.MODE_PRIVATE);

        goal = prefs.getInt("goal", SettingsFragment.DEFAULT_GOAL);
        since_boot = db.getCurrentSteps(); // do not use the value from the sharedPreferences
        int pauseDifference = since_boot - prefs.getInt("pauseCount", since_boot);

        // register a sensorlistener to live update the UI if a step is taken
        if (!prefs.contains("pauseCount")) {
            SensorManager sm =
                    (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);
            Sensor sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
            if (sensor == null) {
                new AlertDialog.Builder(getActivity()).setTitle(R.string.no_sensor)
                        .setMessage(R.string.no_sensor_explain)
                        .setOnDismissListener(new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(final DialogInterface dialogInterface) {
                                getActivity().finish();
                            }
                        }).setNeutralButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(final DialogInterface dialogInterface, int i) {
                                dialogInterface.dismiss();
                            }
                        }).create().show();
            } else {
                sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, 0);
            }
        }

        since_boot -= pauseDifference;

        total_start = db.getTotalWithoutToday();
        total_days = db.getDays();

        db.close();

        stepsDistanceChanged();
    }

    /**
     * Call this method if the Fragment should update the "steps"/"km" text in
     * the pie graph as well as the pie and the bars graphs.
     */
    private void stepsDistanceChanged() {
        if (showSteps) {
            getView().findViewById(R.id.stepsimageView).setVisibility(View.VISIBLE);
            getView().findViewById(R.id.unit).setVisibility(View.GONE);
            ((TextView) getView().findViewById(R.id.unit)).setText("Steps");

        } else {
            String unit = getActivity().getSharedPreferences("FabFit", Context.MODE_PRIVATE)
                    .getString("stepsize_unit", SettingsFragment.DEFAULT_STEP_UNIT);
            if (unit.equals("cm")) {
                unit = "km";
            } else {
                unit = "mi";
            }
            getView().findViewById(R.id.stepsimageView).setVisibility(View.GONE);
            ((TextView) getView().findViewById(R.id.unit)).setText(unit);
            getView().findViewById(R.id.unit).setVisibility(View.VISIBLE);
        }

        updatePie();
    }

    @Override
    public void onPause() {
        super.onPause();
        try {
            SensorManager sm =
                    (SensorManager) getActivity().getSystemService(Context.SENSOR_SERVICE);
            sm.unregisterListener(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
        Database db = Database.getInstance(getActivity());
        db.saveCurrentSteps(since_boot);
        db.close();
    }


    @Override
    public void onAccuracyChanged(final Sensor sensor, int accuracy) {
        // won't happen
    }

    @Override
    public void onSensorChanged(final SensorEvent event) {
        if (BuildConfig.DEBUG)
            Logger.log("UI - sensorChanged | todayOffset: " + todayOffset + " since boot: " +
                    event.values[0]);
        if (event.values[0] > Integer.MAX_VALUE || event.values[0] == 0) {
            return;
        }
        if (todayOffset == Integer.MIN_VALUE) {
            // no values for today
            // we dont know when the reboot was, so set todays steps to 0 by
            // initializing them with -STEPS_SINCE_BOOT
            todayOffset = -(int) event.values[0];
            Database db = Database.getInstance(getActivity());
            db.insertNewDay(Util.getToday(), (int) event.values[0]);
            db.close();
        }
        since_boot = (int) event.values[0];
        updatePie();
    }

    /**
     * Updates the pie graph to show todays steps/distance as well as the
     * yesterday and total values. Should be called when switching from step
     * count to distance.
     */
    private void updatePie() {
        if (BuildConfig.DEBUG) Logger.log("UI - update steps: " + since_boot);
        // todayOffset might still be Integer.MIN_VALUE on first start
        int steps_today = Math.max(todayOffset + since_boot, 0);
        sliceCurrent.setValue(steps_today);
        if (goal - steps_today > 0) {
            // goal not reached yet
            if (pg.getData().size() == 1) {
                // can happen if the goal value was changed: old goal value was
                // reached but now there are some steps missing for the new goal
                pg.addPieSlice(sliceGoal);
            }
            sliceGoal.setValue(goal - steps_today);
        } else {
            // goal reached
            pg.clearChart();
            pg.addPieSlice(sliceCurrent);
        }
        pg.update();
        if (showSteps) {
            stepsView.setText(formatter.format(steps_today));
            totalView.setText(formatter.format(total_start + steps_today));
            averageView.setText(formatter.format((total_start + steps_today) / total_days));
            caloriesView.setText(formatter.format(calculateCalories(steps_today)));
        } else {
            // update only every 10 steps when displaying distance
            SharedPreferences prefs =
                    Objects.requireNonNull(getActivity()).getSharedPreferences("FabFit", Context.MODE_PRIVATE);
            float stepsize = prefs.getFloat("stepsize_value", SettingsFragment.DEFAULT_STEP_SIZE);
            float distance_today = steps_today * stepsize;
            float distance_total = (total_start + steps_today) * stepsize;
            if (prefs.getString("stepsize_unit", SettingsFragment.DEFAULT_STEP_UNIT)
                    .equals("cm")) {
                distance_today /= 100000;
                distance_total /= 100000;
            } else {
                distance_today /= 5280;
                distance_total /= 5280;
            }

            stepsView.setText(formatter.format(distance_today));
            totalView.setText(formatter.format(distance_total));
            averageView.setText(formatter.format(distance_total / total_days));
            caloriesView.setText(formatter.format(calculateCalories(steps_today)));

        }
    }

    public double calculateCalories(int stepscount) {

        double mCalories =
                (SettingsFragment.DEFAULT_WEIGHT * (METRIC_AVG_FACTOR))
                        * 75 * stepscount / 100000.0;
        //75-step size
        //weight
        return mCalories;
    }


}
