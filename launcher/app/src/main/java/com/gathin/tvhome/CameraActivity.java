package com.gathin.tvhome;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One camera: its latest snapshot and what it reports on the left; on the right its clip history
 * from the NAS archive (24, 48 or 72 hours; all, motion or doorbell presses), grouped by day, each
 * with its time, what started it and a thumbnail. OK on a clip plays it full screen. The list
 * loads 40 clips at a time as focus nears the end; thumbnails load off the main thread. Every
 * refresh re-reads the newest page quietly: new clips join the top, lengths and thumbnails that
 * weren't ready fill in, and the focused row stays where it is.
 * "New snapshot" asks the camera for a fresh picture (at most once a minute; it costs battery).
 * Nothing here arms, disarms, deletes or changes anything in Blink or on the NAS.
 */
public class CameraActivity extends Screen {

    static final String EXTRA = "camera";
    private static final int PAGE = 40, PLAY = 7;
    private static final int[] HOURS = {24, 48, 72};
    private static final String[] KINDS = {"all", "motion", "doorbell"};
    private static final String[] KIND_LABELS = {"All", "Motion", "Doorbell"};

    private String name;
    private ImageView shot;
    private TextView shotAge, note, clipsCount, empty;
    private LinearLayout dots, facts;
    private Plate snap;
    private final List<Plate> hourChips = new ArrayList<>(), kindChips = new ArrayList<>();
    private RecyclerView list;
    private final Adapter adapter = new Adapter();

    private int hours = 24, kindAt = 0;
    private final List<Live.Clip> clips = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();   // String day heading, or Live.Clip
    private int next = -1, gen = 0;
    private boolean loading;
    private Live.ClipPage lastPage;

    @Override
    protected String[] crumb() {
        name = getIntent().getStringExtra(EXTRA);
        if (name == null) name = "Camera";
        return new String[]{"HOME", "CAMERAS", name.trim().toUpperCase(Locale.US)};
    }

    @Override
    protected void build() {
        LinearLayout cols = Ui.hbox(this);
        cols.setClipChildren(false);
        cols.addView(buildLeft(), new LinearLayout.LayoutParams(Ui.dp(this, 352), -1));
        View right = buildRight();
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1f);
        rp.leftMargin = Ui.dp(this, 28);
        cols.addView(right, rp);
        body.addView(cols, new FrameLayout.LayoutParams(-1, -1));
        foot("OK", "play clip", "◀ ▶", "move", "BACK", "cameras");
        hourChips.get(0).requestFocus();
        render(Live.cams());
        load(true);
    }

    // ---- left: the camera -----------------------------------------------------------------------

    private View buildLeft() {
        LinearLayout left = Ui.vbox(this);
        left.setClipChildren(false);
        left.addView(Ui.single(Ui.text(this, name.trim(), 22, t.text, 300)));
        dots = Ui.hbox(this);
        left.addView(dots, Ui.lpTop(this, -1, -2, 6));

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF0B0F14);
        final float r = Ui.dp(this, Ui.R_CARD);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        frame.setClipToOutline(true);
        shot = new ImageView(this);
        shot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(shot, new FrameLayout.LayoutParams(-1, -1));
        shotAge = Ui.text(this, "loading snapshot…", 8.5f, 0xE6FFFFFF, 500);
        shotAge.setBackground(Ui.pill(this, 0x99000000));
        shotAge.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
        frame.addView(shotAge, Ui.at(this, Gravity.BOTTOM | Gravity.START, 9, 0, 0, 9));
        left.addView(frame, Ui.lpTop(this, -1, Ui.dp(this, 198), 10));

        LinearLayout row = Ui.hbox(this);
        row.setClipChildren(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        snap = Plate.of(this, t, Plate.Kind.BUTTON);
        snap.setGravity(Gravity.CENTER);
        snap.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        snap.addView(Ui.text(this, "New snapshot", 10.5f, t.text, 500));
        snap.setOnClickListener(v -> askSnap());
        snap.setOnKeyListener((v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { focusClipsOrChip(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
        });
        row.addView(snap, new LinearLayout.LayoutParams(-2, Ui.dp(this, 30)));
        note = Ui.single(Ui.text(this, "  uses a little battery · once a minute", 8.5f, t.dim, 300));
        row.addView(note, new LinearLayout.LayoutParams(0, -2, 1f));
        left.addView(row, Ui.lpTop(this, -1, -2, 12));

        facts = Ui.vbox(this);
        left.addView(facts, Ui.lpTop(this, -1, -2, 12));
        return left;
    }

    // ---- right: the clips -----------------------------------------------------------------------

    private View buildRight() {
        LinearLayout col = Ui.vbox(this);
        col.setClipChildren(false);
        LinearLayout head = Ui.hbox(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.eyebrow(this, t, "Clips"), new LinearLayout.LayoutParams(0, -2, 1f));
        clipsCount = Ui.text(this, "", 9, t.dim, 300);
        head.addView(clipsCount);
        col.addView(head, Ui.lpTop(this, -1, -2, 8));

        LinearLayout chips = Ui.hbox(this);
        chips.setClipChildren(false);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < HOURS.length; i++) {
            final int h = HOURS[i];
            Plate c = chip(HOURS[i] + " h");
            c.setOnClickListener(v -> { if (hours != h) { hours = h; paintChips(); load(true); } });
            chips.addView(c, chipLp(i == 0 ? 0 : 8));
            hourChips.add(c);
        }
        for (int i = 0; i < KINDS.length; i++) {
            final int k = i;
            Plate c = chip(KIND_LABELS[i]);
            c.setOnClickListener(v -> { if (kindAt != k) { kindAt = k; paintChips(); load(true); } });
            chips.addView(c, chipLp(i == 0 ? 22 : 8));
            kindChips.add(c);
        }
        final List<Plate> all = new ArrayList<>(hourChips);
        all.addAll(kindChips);
        for (int i = 0; i < all.size(); i++) {
            final int idx = i, last = all.size() - 1;
            all.get(i).setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (key) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:  if (idx == 0) { snap.requestFocus(); return true; } return false;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: return idx == last;
                    case KeyEvent.KEYCODE_DPAD_UP:    return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:  focusRow(firstClipRow()); return true;
                    default: return false;
                }
            });
        }
        paintChips();
        col.addView(chips, Ui.lpTop(this, -1, Ui.dp(this, 30), 10));

        FrameLayout box = new FrameLayout(this);
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setItemAnimator(null);
        adapter.setHasStableIds(true);   // a refresh that adds clips keeps the focus on the same row
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        box.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = Ui.text(this, "", 10, t.dim, 300);
        box.addView(empty, Ui.at(this, Gravity.TOP | Gravity.START, 2, 18, 0, 0));
        col.addView(box, new LinearLayout.LayoutParams(-1, 0, 1f));
        return col;
    }

    private Plate chip(String label) {
        Plate c = Plate.of(this, t, Plate.Kind.BUTTON);
        c.setGravity(Gravity.CENTER);
        c.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        LinearLayout in = Ui.hbox(this);
        in.setGravity(Gravity.CENTER_VERTICAL);
        View d = Ui.dot(this, t.gold, 5);
        in.addView(d);
        TextView w = Ui.text(this, label, 10, t.text, 500);
        LinearLayout.LayoutParams wp = Ui.lp(-2, -2); wp.leftMargin = Ui.dp(this, 5);
        in.addView(w, wp);
        c.addView(in);
        c.setTag(new View[]{d, w});
        return c;
    }

    private LinearLayout.LayoutParams chipLp(int leftDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -1);
        lp.leftMargin = Ui.dp(this, leftDp);
        return lp;
    }

    /** The chosen chip in each group: a gold dot and gold words. */
    private void paintChips() {
        for (int i = 0; i < hourChips.size(); i++) paintChip(hourChips.get(i), HOURS[i] == hours);
        for (int i = 0; i < kindChips.size(); i++) paintChip(kindChips.get(i), i == kindAt);
    }

    private void paintChip(Plate c, boolean on) {
        View[] v = (View[]) c.getTag();
        v[0].setVisibility(on ? View.VISIBLE : View.GONE);
        TextView w = (TextView) v[1];
        w.setTextColor(on ? t.goldText : t.dim);
        Ui.weigh(w, on ? 600 : 400);
        ((LinearLayout.LayoutParams) w.getLayoutParams()).leftMargin = on ? Ui.dp(this, 5) : 0;
    }

    private Plate selectedHourChip() {
        for (int i = 0; i < HOURS.length; i++) if (HOURS[i] == hours) return hourChips.get(i);
        return hourChips.get(0);
    }

    // ---- loading --------------------------------------------------------------------------------

    /** Load the first page for the chosen filters (fresh), or the next page onto the list. */
    private void load(boolean fresh) {
        if (!fresh && (loading || next < 0)) return;
        final int my = fresh ? ++gen : gen;
        final int offset = fresh ? 0 : next;
        final int h = hours;
        final String kind = KINDS[kindAt];
        loading = true;
        if (fresh) {
            clipsCount.setText("loading…");
            if (clips.isEmpty()) empty.setText("Loading clips from the archive…");
        }
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Live.ClipPage p = Live.fetchClipsBlocking(app, name, h, kind, offset, PAGE);
            main.post(() -> {
                if (my != gen) return;   // the filters changed while this was on its way
                loading = false;
                lastPage = p;
                next = p.next;
                if (fresh) { clips.clear(); clips.addAll(p.clips); lastClip = null; regroup(); }
                else append(p.clips);
                if (fresh) list.scrollToPosition(0);
                showCount(p, h);
                empty.setText(!p.ok ? "Clip history isn't answering: is the Blink bridge running on the hub?"
                        : !clips.isEmpty() ? ""
                        : kindAt == 2 ? "No doorbell presses in the last " + h + " hours."
                        : "No " + (kindAt == 1 ? "motion " : "") + "clips in the last " + h + " hours.");
            });
        }, "mills-clips").start();
    }

    /** Clips newest first, with a heading wherever the day changes. */
    private void regroup() {
        rows.clear();
        String day = null;
        for (Live.Clip c : clips) {
            String d = dayOf(c.at);
            if (!d.equals(day)) { rows.add(d); day = d; }
            rows.add(c);
        }
        adapter.notifyDataSetChanged();
    }

    /** A further page: add its rows after the last, so the focused row stays put. */
    private void append(List<Live.Clip> more) {
        int from = rows.size();
        String day = null;
        for (int i = rows.size() - 1; i >= 0 && day == null; i--) if (rows.get(i) instanceof Live.Clip) day = dayOf(((Live.Clip) rows.get(i)).at);
        for (Live.Clip c : more) {
            clips.add(c);
            String d = dayOf(c.at);
            if (!d.equals(day)) { rows.add(d); day = d; }
            rows.add(c);
        }
        adapter.notifyItemRangeInserted(from, rows.size() - from);
    }

    /** The next (d = 1) or previous (d = -1) clip row from pos, skipping day headings; -1 if none. */
    private int clipRowFrom(int pos, int d) {
        for (int i = pos + d; i >= 0 && i < rows.size(); i += d) if (rows.get(i) instanceof Live.Clip) return i;
        return -1;
    }

    private static String dayOf(long at) {
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis(at);
        String date = new SimpleDateFormat("EEE MMM d", Locale.US).format(new Date(at));
        if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR)) {
            int dd = now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR);
            if (dd == 0) return "Today · " + date;
            if (dd == 1) return "Yesterday · " + date;
        }
        return date;
    }

    private int firstClipRow() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof Live.Clip) return i;
        return -1;
    }

    private int rowOfClip(int clipIndex) {
        if (clipIndex < 0 || clipIndex >= clips.size()) return -1;
        Live.Clip want = clips.get(clipIndex);
        for (int i = 0; i < rows.size(); i++) if (rows.get(i) == want) return i;
        return -1;
    }

    /** Scroll a row into view and focus it (it may need a layout pass first). */
    private void focusRow(int pos) {
        if (pos < 0) return;
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(pos);
        if (vh != null && vh.itemView.isFocusable()) { vh.itemView.requestFocus(); return; }
        list.scrollToPosition(pos);
        list.post(() -> {
            RecyclerView.ViewHolder v2 = list.findViewHolderForAdapterPosition(pos);
            if (v2 != null) v2.itemView.requestFocus();
        });
    }

    /** ▶ from New snapshot: back on the clip row last focused, else the first one, else the chips. */
    private void focusClipsOrChip() {
        int at = lastClip == null ? -1 : rows.indexOf(lastClip);
        if (at < 0) at = firstClipRow();
        if (at >= 0) focusRow(at); else selectedHourChip().requestFocus();
    }

    private Live.Clip lastClip;

    private void play(int clipIndex) {
        int n = clips.size();
        String[] urls = new String[n], whats = new String[n];
        long[] times = new long[n];
        boolean[] arch = new boolean[n];
        String host = Live.blinkHost(getApplicationContext());
        for (int i = 0; i < n; i++) {
            Live.Clip c = clips.get(i);
            urls[i] = host + c.url;
            whats[i] = c.what();
            times[i] = c.at;
            arch[i] = c.archived;
        }
        startActivityForResult(new Intent(this, ClipPlayerActivity.class)
                .putExtra(ClipPlayerActivity.URLS, urls).putExtra(ClipPlayerActivity.WHATS, whats)
                .putExtra(ClipPlayerActivity.TIMES, times).putExtra(ClipPlayerActivity.ARCHIVED, arch)
                .putExtra(ClipPlayerActivity.CAMERA, name).putExtra(ClipPlayerActivity.INDEX, clipIndex), PLAY);
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PLAY || data == null) return;
        final int pos = rowOfClip(data.getIntExtra(ClipPlayerActivity.INDEX, -1));   // back on the clip last shown
        if (pos >= 0) list.post(() -> focusRow(pos));
    }

    // ---- rows -----------------------------------------------------------------------------------

    private static final int HEADING = 0, CLIP = 1;

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemCount() { return rows.size(); }
        @Override public long getItemId(int p) {
            Object o = rows.get(p);
            if (o instanceof String) return ("day:" + o).hashCode();
            String id = ((Live.Clip) o).id;
            try { return Long.parseLong(id) + 1_000_000_000_000L; } catch (Exception e) { return ("clip:" + id).hashCode(); }
        }
        @Override public int getItemViewType(int p) { return rows.get(p) instanceof String ? HEADING : CLIP; }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
            if (type == HEADING) {
                TextView h = Ui.groupHeading(CameraActivity.this, t, "");
                h.setPadding(Ui.dp(CameraActivity.this, 4), Ui.dp(CameraActivity.this, 12), 0, Ui.dp(CameraActivity.this, 5));
                h.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                return new RecyclerView.ViewHolder(h) {};
            }
            return new ClipRow(CameraActivity.this);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder vh, int p) {
            Object o = rows.get(p);
            if (o instanceof String) ((TextView) vh.itemView).setText(((String) o).toUpperCase(Locale.US));
            else ((ClipRow) vh).bind((Live.Clip) o);
        }
    }

    private final class ClipRow extends RecyclerView.ViewHolder {
        final Plate plate;
        final ImageView img;
        final TextView time, what, where;
        final View dot;
        Live.Clip clip;

        ClipRow(android.content.Context c) {
            super(Plate.of(c, t, Plate.Kind.ROW));
            plate = (Plate) itemView;
            plate.setOrientation(LinearLayout.HORIZONTAL);
            plate.setGravity(Gravity.CENTER_VERTICAL);
            plate.setPadding(Ui.dp(c, 6), Ui.dp(c, 5), Ui.dp(c, 12), Ui.dp(c, 5));
            plate.underlineAt(104, 6);
            plate.setLayoutParams(new RecyclerView.LayoutParams(-1, Ui.dp(c, 58)));

            FrameLayout th = new FrameLayout(c);
            th.setBackgroundColor(0xFF0B0F14);
            final float r = Ui.dp(c, 6);
            th.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
            });
            th.setClipToOutline(true);
            img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            th.addView(img, new FrameLayout.LayoutParams(-1, -1));
            plate.addView(th, new LinearLayout.LayoutParams(Ui.dp(c, 85), Ui.dp(c, 48)));

            LinearLayout words = Ui.vbox(c);
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1f);
            wp.leftMargin = Ui.dp(c, 12);
            plate.addView(words, wp);
            time = Ui.single(Ui.text(c, "", 12, t.text, 500));
            words.addView(time);
            LinearLayout r2 = Ui.hbox(c);
            r2.setGravity(Gravity.CENTER_VERTICAL);
            dot = Ui.dot(c, t.teal, 5);
            r2.addView(dot);
            what = Ui.single(Ui.text(c, "", 9.5f, t.dim, 400));
            LinearLayout.LayoutParams ap = Ui.lp(-2, -2); ap.leftMargin = Ui.dp(c, 6);
            r2.addView(what, ap);
            words.addView(r2, Ui.lpTop(c, -2, -2, 4));
            where = Ui.text(c, "", 8.5f, t.dim, 300);
            plate.addView(where);

            plate.setOnClickListener(v -> { if (clip != null) play(clips.indexOf(clip)); });
            plate.onFocus((p, has) -> {
                if (!has) return;
                lastClip = clip;
                int pos = getBindingAdapterPosition();
                if (pos >= rows.size() - 8) load(false);   // the next page, before the end is reached
            });
            plate.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                switch (key) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:  snap.requestFocus(); return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: return true;
                    case KeyEvent.KEYCODE_DPAD_UP: {
                        int up = clipRowFrom(pos, -1);
                        if (up < 0) selectedHourChip().requestFocus(); else focusRow(up);
                        return true;
                    }
                    case KeyEvent.KEYCODE_DPAD_DOWN: {   // the end stays put (and asks for more), never wraps
                        int down = clipRowFrom(pos, 1);
                        if (down < 0) load(false); else focusRow(down);
                        return true;
                    }
                    default: return false;
                }
            });
        }

        void bind(Live.Clip c) {
            clip = c;
            time.setText(new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date(c.at)));
            String len = c.length();
            what.setText(c.what() + (len != null ? " · " + len : ""));
            boolean ring = "doorbell".equals(c.kind);
            ((android.graphics.drawable.GradientDrawable) dot.getBackground()).setColor(ring ? t.gold : "motion".equals(c.kind) ? t.teal : t.idle);
            where.setText(c.archived ? "NAS" : "cloud");
            where.setTextColor(c.archived ? t.dim : t.orangeText);
            ClipThumbs.into(CameraActivity.this, c, img);
        }
    }

    // ---- the camera's own facts -----------------------------------------------------------------

    private Bitmap shown;

    @Override
    protected void refresh() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Live.Cams c = Live.fetchCamsBlocking(app);
            Live.Cam cam = null;
            for (Live.Cam x : c.cams) if (x.name.equals(name)) cam = x;
            // only downloaded when Blink has a new snapshot
            Bitmap b = cam != null && cam.hasThumb ? Live.camSnapshot(app, cam) : null;
            main.post(() -> {
                if (b != null && b != shown) { shown = b; shot.setImageBitmap(b); }
                render(c);
            });
        }, "mills-cam").start();
        quietRefresh();
    }

    /** Re-read the newest page and fold it in; never while a page is loading or the filters just changed. */
    private void quietRefresh() {
        if (loading || lastPage == null || !lastPage.ok) return;
        final int my = gen, h = hours;
        final String kind = KINDS[kindAt];
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Live.ClipPage p = Live.fetchClipsBlocking(app, name, h, kind, 0, PAGE);
            main.post(() -> { if (my == gen && !loading && p.ok && !isFinishing()) merge(p, h); });
        }, "mills-clips-refresh").start();
    }

    private void merge(Live.ClipPage p, int h) {
        java.util.Map<String, Live.Clip> have = new java.util.HashMap<>();
        for (Live.Clip c : clips) have.put(c.id, c);
        long newest = clips.isEmpty() ? 0 : clips.get(0).at;
        List<Live.Clip> added = new ArrayList<>();
        for (Live.Clip c : p.clips) {
            Live.Clip old = have.get(c.id);
            if (old == null) { if (c.at >= newest) added.add(c); continue; }
            boolean changed = false;
            if (old.duration == null && c.duration != null) { old.duration = c.duration; changed = true; }
            if (!old.archived && c.archived) { old.archived = true; changed = true; }
            if (changed || ClipThumbs.missing(old)) {   // rebinding also asks again for a thumbnail that failed
                int r = rows.indexOf(old);
                if (r >= 0) adapter.notifyItemChanged(r);
            }
        }
        if (!added.isEmpty()) {
            clips.addAll(0, added);
            if (next >= 0) next += added.size();   // the pages behind moved down by as many
            regroup();
        }
        lastPage = p;
        showCount(p, h);
        if (!clips.isEmpty()) empty.setText("");
    }

    private void showCount(Live.ClipPage p, int h) {
        String what = kindAt == 0 ? "clips" : kindAt == 1 ? "motion clips" : "doorbell presses";
        clipsCount.setText(!p.ok ? "" : p.total + " " + (p.total == 1 ? what.replace("clips", "clip").replace("presses", "press") : what)
                + " · " + h + " h");
    }

    private void askSnap() {
        note.setText("  asking the camera…");
        new Thread(() -> {
            String msg = Live.requestSnap(getApplicationContext(), name);
            main.post(() -> note.setText("  " + msg));
            try { Thread.sleep(16_000); } catch (InterruptedException ignored) {}
            main.post(this::refresh);
        }, "mills-snap").start();
    }

    private void render(Live.Cams c) {
        Live.Cam m = null;
        for (Live.Cam x : c.cams) if (x.name.equals(name)) m = x;
        boolean fresh = c.fresh();
        dots.removeAllViews();
        if (m == null) {
            dots.addView(dotWord(Ui.Status.IDLE, c.chip(), 9.5f));
        } else {
            String on = !fresh ? Ui.Status.IDLE : !m.online ? Ui.Status.BAD : m.batteryLow() || m.wifiWeak() ? Ui.Status.WARN : Ui.Status.OK;
            dots.addView(dotWord(on, !fresh ? "no reading" : !m.online ? "offline" : "online", 9.5f));
            String arm = !fresh || m.armed == null ? Ui.Status.IDLE : !m.armed || Boolean.FALSE.equals(m.motion) ? Ui.Status.WARN : Ui.Status.OK;
            LinearLayout.LayoutParams ap = Ui.lp(-2, -2); ap.leftMargin = Ui.dp(this, 12);
            dots.addView(dotWord(arm, m.armed == null ? "armed —" : !m.armed ? "disarmed" : Boolean.FALSE.equals(m.motion) ? "motion off" : "armed", 9.5f), ap);
            TextView kind = Ui.text(this, m.doorbell ? "Video Doorbell" : "sedona".equals(m.type) ? "Outdoor 4" : "Outdoor camera", 9.5f, m.doorbell ? t.goldText : t.dim, m.doorbell ? 600 : 300);
            LinearLayout.LayoutParams kp = Ui.lp(-2, -2); kp.leftMargin = Ui.dp(this, 12);
            dots.addView(kind, kp);
        }
        Live.CamEvent last = c.lastFor(name);
        shotAge.setText(last != null ? "latest snapshot · " + last.what() + " " + Live.ago(last.at) : "latest snapshot Blink has");

        facts.removeAllViews();
        String wifi = m == null ? null : m.wifiWord();
        long lm = m == null ? 0 : Math.max(m.lastMotionAt, last != null && !"snapshot".equals(last.what()) ? last.at : 0);
        factRow("Battery", m == null || m.battery == null ? "—" : m.battery, m != null && m.batteryLow() ? t.orangeText : 0,
                "Wi-Fi", wifi == null ? "—" : wifi, m != null && m.wifiWeak() ? t.orangeText : 0, 0);
        factRow("Temperature", m == null || m.tempF == null ? "—" : m.tempF + "°F", 0,
                "Motion alerts", m == null || m.motion == null ? "—" : m.motion ? "on" : "off", m != null && Boolean.FALSE.equals(m.motion) ? t.orangeText : 0, 6);
        factRow("Last motion", lm > 0 ? Live.ago(lm) : "none known", 0,
                "Today", m == null ? "—" : m.clipsToday == 1 ? "1 clip" : m.clipsToday + " clips", 0, 6);
    }

    private void factRow(String k1, String v1, int c1, String k2, String v2, int c2, int topDp) {
        LinearLayout r = Ui.hbox(this);
        r.addView(Ui.kv(this, t, k1, v1, c1, 9.5f), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -2, 1f);
        p2.leftMargin = Ui.dp(this, 18);
        r.addView(Ui.kv(this, t, k2, v2, c2, 9.5f), p2);
        facts.addView(r, Ui.lpTop(this, -1, -2, topDp));
    }
}
