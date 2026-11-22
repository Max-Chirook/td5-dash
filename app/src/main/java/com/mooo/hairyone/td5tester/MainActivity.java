package com.mooo.hairyone.td5tester;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import com.google.android.material.tabs.TabLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.mooo.hairyone.td5tester.fragments.ActuatorFragment;
import com.mooo.hairyone.td5tester.fragments.ConnectFragment;
import com.mooo.hairyone.td5tester.fragments.SettingsFragment;
import com.mooo.hairyone.td5tester.fragments.DashboardFragment;
import com.mooo.hairyone.td5tester.fragments.FuelDemandFragment;
import com.mooo.hairyone.td5tester.fragments.GraphFragment;
import com.mooo.hairyone.td5tester.fragments.InjectorFragment;
import com.mooo.hairyone.td5tester.fragments.TemperatureAndPressureFragment;

import org.apache.log4j.Logger;

public class MainActivity extends AppCompatActivity {
    Logger log = Log4jHelper.getLogger(this.getClass());

    private Toolbar toolbar;
    private TabLayout tabLayout;
    private ViewPager viewPager;

    // Single task: the boot receiver and the cable both start this activity,
    // and they now land on the one already open instead of stacking a copy.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            EcuSession.get(this).cableAttached();
        }
    }

    @Override
    protected void onDestroy() {
        // Leaving the app for good (Back) lets go of the ECU; a rebuild for a
        // new window size keeps the link running.
        if (isFinishing()) EcuSession.get(this).shutdown();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String log_filename = MainActivity.this.getExternalFilesDir(null).getAbsolutePath() + File.separator + "td5tester.log";
        Log4jHelper.configure(log_filename);
        if(!(Thread.getDefaultUncaughtExceptionHandler() instanceof CustomExceptionHandler)) {
            Thread.setDefaultUncaughtExceptionHandler(new CustomExceptionHandler());
        }
        log.debug("-------------------------------------------");
        log.info(String.format("logging to %s", log_filename));

        // Created now, so it watches the cable from the start.
        EcuSession.get(this);
        // Follows the readings from the start, whichever tab is showing.
        TripComputer.get(this);

        setContentView(R.layout.activity_main);


        //getSupportActionBar().setDisplayShowHomeEnabled(false);
        //getSupportActionBar().setDisplayShowTitleEnabled(true);
        //getSupportActionBar().setDisplayUseLogoEnabled(true);

        viewPager = (ViewPager) findViewById(R.id.viewpager);
        // https://stackoverflow.com/questions/8348707/prevent-viewpager-from-destroying-off-screen-views
        viewPager.setOffscreenPageLimit(7);
        setupViewPager(viewPager);

        tabLayout = (TabLayout) findViewById(R.id.tabs);
        tabLayout.setupWithViewPager(viewPager);
        // Seven fixed tabs do not fit a half-screen window.
        float widthDp = getResources().getConfiguration().screenWidthDp;
        // The link opens on its own, so the app starts on the live gauges.
        if (savedInstanceState == null) viewPager.setCurrentItem(1, false);
        tabLayout.setTabMode(widthDp < 700
                ? com.google.android.material.tabs.TabLayout.MODE_SCROLLABLE
                : com.google.android.material.tabs.TabLayout.MODE_FIXED);
    }

    private void setupViewPager(ViewPager viewPager) {
        ViewPagerAdapter adapter = new ViewPagerAdapter(getSupportFragmentManager());
        adapter.addFragment(new ConnectFragment(), getString(R.string.tab_connect));
        adapter.addFragment(new DashboardFragment(), getString(R.string.tab_main));
        adapter.addFragment(new InjectorFragment(), getString(R.string.tab_injector));
        adapter.addFragment(new FuelDemandFragment(), getString(R.string.tab_expert));
        adapter.addFragment(new TemperatureAndPressureFragment(), getString(R.string.tab_acc_temp));
        adapter.addFragment(new GraphFragment(), getString(R.string.tab_graph));
        adapter.addFragment(new ActuatorFragment(), getString(R.string.tab_tests));
        adapter.addFragment(new SettingsFragment(), getString(R.string.tab_settings));
        viewPager.setAdapter(adapter);
    }

    class ViewPagerAdapter extends FragmentPagerAdapter {
        private final List<Fragment> mFragmentList = new ArrayList<>();
        private final List<String> mFragmentTitleList = new ArrayList<>();

        public ViewPagerAdapter(FragmentManager manager) {
            super(manager);
        }

        @Override
        public Fragment getItem(int position) {
            return mFragmentList.get(position);
        }

        @Override
        public int getCount() {
            return mFragmentList.size();
        }

        public void addFragment(Fragment fragment, String title) {
            mFragmentList.add(fragment);
            mFragmentTitleList.add(title);
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return mFragmentTitleList.get(position);
        }
    }

}