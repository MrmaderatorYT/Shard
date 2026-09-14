package com.ccs.shard;

import android.os.Bundle;

import com.ccs.shard.base.BaseActivity;
import com.ccs.shard.core.DailyNotes;

/** Widget/deep-link router for today's durable daily note. */
public final class DailyNoteActivity extends BaseActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        repo.open(() -> {
            if (repo.vault().isSharedStorage() && !repo.vault().hasFullFileAccess()) {
                startActivity(new android.content.Intent(this, HomeActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
                return;
            }
            DailyNotes.open(this, repo, new java.util.Date());
            finish();
        });
    }
}
