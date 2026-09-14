package com.ccs.shard.benchmark;

import androidx.benchmark.macro.CompilationMode;
import androidx.benchmark.macro.FrameTimingMetric;
import androidx.benchmark.macro.MacrobenchmarkScope;
import androidx.benchmark.macro.StartupMode;
import androidx.benchmark.macro.StartupTimingMetric;
import androidx.benchmark.macro.junit4.MacrobenchmarkRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Regression benchmark for cold start; results are written by AndroidX Benchmark. */
@RunWith(AndroidJUnit4.class)
public class ShardMacrobenchmark {
    private static final String PACKAGE_NAME = "com.ccs.shard";

    @Rule public final MacrobenchmarkRule benchmarkRule = new MacrobenchmarkRule();

    @Test public void coldStartup() {
        benchmarkRule.measureRepeated(
                PACKAGE_NAME,
                Collections.singletonList(new StartupTimingMetric()),
                CompilationMode.DEFAULT,
                StartupMode.COLD,
                5,
                new Function1<MacrobenchmarkScope, Unit>() {
                    @Override public Unit invoke(MacrobenchmarkScope scope) {
                        scope.pressHome();
                        return Unit.INSTANCE;
                    }
                },
                new Function1<MacrobenchmarkScope, Unit>() {
                    @Override public Unit invoke(MacrobenchmarkScope scope) {
                        scope.startActivityAndWait();
                        return Unit.INSTANCE;
                    }
                });
    }

    /** Measures list-frame jank independently from cold-start timing. */
    @Test public void vaultListScroll() {
        benchmarkRule.measureRepeated(
                PACKAGE_NAME,
                Collections.singletonList(new FrameTimingMetric()),
                CompilationMode.DEFAULT,
                StartupMode.WARM,
                5,
                new Function1<MacrobenchmarkScope, Unit>() {
                    @Override public Unit invoke(MacrobenchmarkScope scope) {
                        scope.startActivityAndWait();
                        return Unit.INSTANCE;
                    }
                },
                new Function1<MacrobenchmarkScope, Unit>() {
                    @Override public Unit invoke(MacrobenchmarkScope scope) {
                        scope.getDevice().swipe(540, 1500, 540, 500, 12);
                        return Unit.INSTANCE;
                    }
                });
    }
}
