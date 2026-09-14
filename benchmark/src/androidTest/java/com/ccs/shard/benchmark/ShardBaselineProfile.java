package com.ccs.shard.benchmark;

import androidx.benchmark.macro.MacrobenchmarkScope;
import androidx.benchmark.macro.junit4.BaselineProfileRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Captures the primary launch flow that is packaged into release builds. */
@RunWith(AndroidJUnit4.class)
public class ShardBaselineProfile {
    private static final String PACKAGE_NAME = "com.ccs.shard";

    @Rule public final BaselineProfileRule baselineProfileRule = new BaselineProfileRule();

    @Test public void startupAndVaultBrowse() {
        baselineProfileRule.collect(PACKAGE_NAME, new Function1<MacrobenchmarkScope, Unit>() {
            @Override public Unit invoke(MacrobenchmarkScope scope) {
                scope.pressHome();
                scope.startActivityAndWait();
                // Include the RecyclerView bind/scroll path used by large vaults.
                scope.getDevice().swipe(540, 1500, 540, 500, 12);
                return Unit.INSTANCE;
            }
        });
    }
}
