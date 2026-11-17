package com.yadaventerprise.phoneserver.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;
import com.yadaventerprise.phoneserver.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class KeyboardThemeTest {

    @Test
    public void darkThemeResizesWhenKeyboardOpens() {
        assertThemeResizesForIme(R.style.Theme_PhoneServer);
    }

    @Test
    public void homeThemeResizesWhenKeyboardOpens() {
        assertThemeResizesForIme(R.style.Theme_PhoneServer_Home);
    }

    private static void assertThemeResizesForIme(int themeRes) {
        Context context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), themeRes);
        TypedValue value = new TypedValue();
        assertTrue(context.getTheme().resolveAttribute(
                android.R.attr.windowSoftInputMode, value, true));
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                value.data & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
    }
}
