package re.zyg.fri.manager;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.Menu;
import androidx.core.content.ContextCompat;
import android.view.View;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;

/** Shared Material toolbar behavior for every screen. */
public abstract class BaseActivity extends AppCompatActivity {
    public static final String EXTRA_DETAIL = "detail_page";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean light = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat bars = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(light);
        bars.setAppearanceLightNavigationBars(light);
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar == null) {
            return;
        }
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        BottomNavigationView navigation = findViewById(R.id.bottomNav);
        boolean detail = getIntent().getBooleanExtra(EXTRA_DETAIL, false);
        if (navigation != null && detail) {
            navigation.setVisibility(View.GONE);
        }
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(navigation == null || detail);
        }
        if (!detail) {
            setupBottomNavigation();
        }
    }

    private void setupBottomNavigation() {
        final BottomNavigationView navigation = findViewById(R.id.bottomNav);
        if (navigation == null) {
            return;
        }
        final int currentItem = currentBottomItem();
        navigation.setItemActiveIndicatorEnabled(false);
        navigation.setElevation(0f);
        navigation.setSelectedItemId(currentItem);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (BaseActivity.this instanceof MainActivity) {
                    moveTaskToBack(true);
                } else {
                    openPage(MainActivity.class);
                }
            }
        });
        navigation.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(MenuItem item) {
                int id = item.getItemId();
                if (id == currentItem) {
                    return true;
                }
                Class<?> target = bottomTarget(id);
                if (target == null) {
                    return false;
                }
                openPage(target);
                return false;
            }
        });
    }

    private void openPage(Class<?> target) {
        // Reuse each root page so tab changes keep form drafts and output.
        startActivity(new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        overridePendingTransition(0, 0);
    }

    private int currentBottomItem() {
        if (this instanceof GadgetConfigActivity || this instanceof ScriptsActivity) {
            return R.id.nav_script;
        }
        if (this instanceof LogActivity) {
            return R.id.nav_log;
        }
        if (this instanceof ToolsActivity) {
            return R.id.nav_tools;
        }
        return R.id.nav_targets;
    }

    private static Class<?> bottomTarget(int id) {
        if (id == R.id.nav_targets) {
            return MainActivity.class;
        }
        if (id == R.id.nav_script) {
            return ScriptsActivity.class;
        }
        if (id == R.id.nav_log) {
            return LogActivity.class;
        }
        if (id == R.id.nav_tools) {
            return ToolsActivity.class;
        }
        return null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.help, menu);
        menu.findItem(R.id.menu_help).getIcon().setTint(ContextCompat.getColor(this, R.color.zfm_on_surface_variant));
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_help) {
            int body = R.string.help_targets;
            if (this instanceof GadgetConfigActivity || this instanceof ScriptsActivity) body = R.string.help_script;
            else if (this instanceof ToolsActivity) body = R.string.help_tools;
            else if (this instanceof LogActivity) body = R.string.help_log;
            else if (this instanceof TargetEditActivity) body = R.string.help_target;
            else if (this instanceof AppPickerActivity) body = R.string.help_picker;
            Ui.help(this, getString(R.string.action_help), getString(body));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
