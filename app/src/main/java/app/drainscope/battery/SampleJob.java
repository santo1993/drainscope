package app.drainscope.battery;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.Executors;

/**
 * Periodic sampler: records a snapshot of the battery state even when the app
 * is not open, so slow drains become visible over days.
 */
public class SampleJob extends JobService {

    private static final int JOB_ID = 4711;
    private static final long PERIOD_MS = 60L * 60 * 1000L;

    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(ctx, SampleJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS)
                    .build();
            js.schedule(job);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    LiveState live = LiveState.sample(SampleJob.this);
                    HistoryStore.Snapshot s = new HistoryStore.Snapshot();
                    s.ts = System.currentTimeMillis();
                    s.level = live.level;
                    s.chargeUah = live.chargeCounterUah;
                    s.source = "background";
                    new HistoryStore(SampleJob.this).insert(s);
                } catch (Throwable ignored) {
                } finally {
                    jobFinished(params, false);
                }
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
