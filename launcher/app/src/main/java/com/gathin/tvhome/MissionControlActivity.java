package com.gathin.tvhome;

import android.content.Intent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Mission Control · AI Team (blueprint A4). Every agent Mission Control reports, 4 across,
 * nothing hidden. States come straight from Hub's /api/status; when it stops answering the
 * tiles keep their last reading, greyed, with its age, never a green dot that can't be proven.
 * Under the agents, the machines they run on (the Sparks and NAS, the hub's "infra"),
 * built once and updated in place so a refresh never moves the focus. OK on one opens MachineActivity.
 * The agent tiles are built once too (again only when the roster changes), and the screen remembers
 * the tile last focused, agent or machine, so coming back, or a rebuild, lands on it. CH ▼ / CH ▲
 * on the remote jump between the agents and the Machines row below them.
 *
 * Edit layout (JJ, Oct 25): the button top-right (or MENU) turns edit mode on. OK on a card offers
 * Move, Rename, Edit note and Reset; a moved card lifts, ◀ ▶ ▲ ▼ carry it through its own group
 * (agents in the grid, machines in their row), OK drops it, BACK puts it back. Done saves the order,
 * names and notes on Hub (/api/layout), so every screen and every reload shows them; the hub's
 * checks and states are untouched. Nothing here changes a machine or an agent.
 */
public class MissionControlActivity extends Screen {

    private LinearLayout summary;
    private TextView feed;
    private GridLayout grid;
    private LinearLayout machines, machineRow;
    private final java.util.Map<String, MachineTile> machineTiles = new java.util.HashMap<>();
    private final java.util.Map<String, AgentTile> agentTiles = new java.util.HashMap<>();
    private String shownMachines, shownAgents;
    /** The tile last focused: an agent's name or "machine:NAME" (survives leaving the screen). */
    private static String lastFocus;
    /** Opened from the Quick menu's Edit layout: start in edit mode. */
    static final String EXTRA_EDIT = "edit_layout";

    // ---- edit mode: JJ's order, names and notes, saved on Hub -------------------------------------
    private Plate editButton;
    private TextView editLabel;
    private boolean editing, dirty;
    private final java.util.List<String> agentOrder = new java.util.ArrayList<>(), machineOrder = new java.util.ArrayList<>();
    /** "a:NAME" / "m:NAME" -> {label, note}; empty = the hub's default. */
    private final java.util.Map<String, String[]> edits = new java.util.HashMap<>();
    private String liftedTag;                       // the card being moved ("NAME" or "machine:NAME")
    private java.util.List<String> liftedFrom;      // its group's order before the move (BACK restores it)
    private boolean dirtyBeforeLift;

    /** One agent's tile and the views a refresh rewrites. */
    private static final class AgentTile {
        Plate plate;
        TextView name, why, role, host, age;
        LinearLayout dot;
    }

    /** One machine's tile and the views a refresh rewrites. */
    private static final class MachineTile {
        Plate plate;
        TextView name;
        LinearLayout status;
        TextView role, spec;
        View used;            // NAS: the used part of the free-space meter
        LinearLayout meter;
    }

    @Override protected String[] crumb() { return new String[]{"HOME", "MISSION CONTROL", "AI TEAM"}; }

    @Override
    protected void build() {
        summary = Ui.hbox(this);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        body.addView(title("AI Team", summary), new FrameLayout.LayoutParams(-2, -2));

        feed = Ui.text(this, "", 9, t.dim, 300);
        body.addView(feed, Ui.at(this, Gravity.TOP | Gravity.END, 0, 4, 0, 0));

        editButton = Plate.of(this, t, Plate.Kind.BUTTON);
        editButton.setGravity(Gravity.CENTER);
        editButton.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        editLabel = Ui.text(this, "Edit layout", 10, t.text, 500);
        editButton.addView(editLabel);
        editButton.setTag("edit");
        editButton.onFocus((pl, has) -> { if (has) foot("OK", editing ? "save layout" : "edit layout", "▼", "cards", "BACK", editing ? "done" : "command center"); });
        editButton.setOnClickListener(v -> { if (editing) finishEditing(true); else startEditing(); });
        editButton.setOnKeyListener((v, key, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { focusTag(lastFocus); return true; }
            return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        body.addView(editButton, Ui.at(this, Gravity.TOP | Gravity.END, 0, 26, 0, 0));
        editButton.getLayoutParams().height = Ui.dp(this, 30);

        grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setClipChildren(false);
        // Ten agents is three rows: they're sized to fit above the wordmark, and past that it scrolls
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));   // room for a card lifted in edit mode
        LinearLayout col = Ui.vbox(this);
        col.setClipChildren(false);
        col.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);   // inside the content, so a focused edge tile isn't cut
        col.addView(grid, Ui.lp(-1, -2));
        machines = Ui.vbox(this);
        machines.setClipChildren(false);
        col.addView(machines, Ui.lpTop(this, -1, -2, 6));
        scroll.addView(col, new FrameLayout.LayoutParams(-1, -2));
        // body doesn't clip, so the scroll gets a frame that does: rows scrolled out of view stay
        // above the wordmark. The frame is 12dp wider each side so a focused tile's 1.03 isn't cut.
        FrameLayout clip = new FrameLayout(this);
        clip.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1, -1);
        gp.topMargin = Ui.dp(this, 60);
        gp.leftMargin = gp.rightMargin = -Ui.dp(this, 12);
        body.addView(clip, gp);
        foot("OK", "open agent", "BACK", "command center");
        render(Live.mission());
        if (getIntent().getBooleanExtra(EXTRA_EDIT, false)) {
            if (Live.mission().agents.isEmpty()) Live.fetchMission(this, m -> { render(m); if (!editing) startEditing(); });
            else body.post(this::startEditing);
        }
    }

    @Override
    protected void refresh() { Live.fetchMission(this, this::render); }

    private void render(Live.Mission m) {
        if (editing) m = arrangedForEdit(m);
        boolean fresh = m.ok && !m.stale();
        summary.removeAllViews();
        if (m.agents.isEmpty()) {
            summary.addView(dotWord(Ui.Status.IDLE, m.at > 0 ? "No agents reported" : "Not connected to Mission Control", 10));
        } else {
            for (String[] k : Live.Mission.KINDS) {   // 1 up · 2 ready · 1 waiting · 6 not built (zeros left out)
                int c = m.countKind(k[1]);
                if (c > 0) addCount(k[0], c + " " + k[1], fresh);
            }
        }
        feed.setText(editing ? "Edit mode · " + (dirty ? "changes not saved yet" : "no changes yet")
                : fresh ? "Mission Control · " + m.host + " · updated " + Live.ago(m.at)
                : m.at > 0 ? "Not answering · last reading " + Live.ago(m.at) : "Mission Control not connected");
        feed.setTextColor(editing ? t.goldText : t.dim);

        View had = getCurrentFocus();
        String keep = had != null && had.getTag() instanceof String ? (String) had.getTag() : lastFocus;
        StringBuilder names = new StringBuilder();
        for (Live.Agent a : m.agents) names.append(a.name).append('\n');
        boolean rebuilt = false;
        if (!names.toString().equals(shownAgents)) { buildAgents(m); shownAgents = names.toString(); rebuilt = true; }
        for (Live.Agent a : m.agents) fillAgent(agentTiles.get(a.name), a, fresh, m);
        rebuilt |= renderMachines(m, fresh);
        View now = getCurrentFocus();
        if (rebuilt || now == null || !now.isShown()) focusTag(keep);
        if (liftedTag != null) {
            View lv = findTile(liftedTag);
            if (lv instanceof Plate) { lv.requestFocus(); ((Plate) lv).setLifted(true); }
        }
    }

    private View findTile(String tag) {
        View v = grid.findViewWithTag(tag);
        if (v == null && machineRow != null) v = machineRow.findViewWithTag(tag);
        return v;
    }

    /** Focus the tile with this tag (an agent, or "machine:NAME"), else the first agent. */
    private void focusTag(String tag) {
        View v = tag == null ? null : grid.findViewWithTag(tag);
        if (v == null && tag != null && machineRow != null) v = machineRow.findViewWithTag(tag);
        if (v == null && grid.getChildCount() > 0) v = grid.getChildAt(0);
        if (v != null) v.requestFocus();
    }

    /** The agents, 4 across; only when the roster itself changed (names or count). */
    private void buildAgents(Live.Mission m) {
        grid.removeAllViews();
        agentTiles.clear();
        int w = tileWidth();
        int rows = Math.max(1, (m.agents.size() + 3) / 4);
        int room = getResources().getDisplayMetrics().heightPixels - Ui.dp(this, 84 + 62 + 72 + 8);
        // JJ, Oct 22: cards about 5% smaller than the first cut (heights, padding and type at 0.95x)
        int h = Math.max(Ui.dp(this, 85), Math.min(Ui.dp(this, 133), Math.round((room / rows - Ui.dp(this, 12)) * 0.95f)));
        boolean compact = h < Ui.dp(this, 124);
        for (int i = 0; i < m.agents.size(); i++) {
            final Live.Agent a = m.agents.get(i);
            AgentTile at = tile(a, compact);
            agentTiles.put(a.name, at);
            Plate p = at.plate;
            final int col = i % 4, n = m.agents.size();
            final int idx = i;
            p.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP && idx < 4) { editButton.requestFocus(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && col == 0) return true;            // never wrap
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && (col == 3 || idx == n - 1)) return true;
                if (key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN) { toMachines(); return true; }
                if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP) { grid.getChildAt(col).requestFocus(); return true; }
                return false;
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w;
            lp.height = h;
            lp.rightMargin = col < 3 ? Ui.dp(this, 12) : 0;
            lp.bottomMargin = Ui.dp(this, 12);
            grid.addView(p, lp);
        }
    }

    /** CH ▼ from the agents: the Machines row, on the machine last focused there. */
    private void toMachines() {
        if (machineRow == null || machineRow.getChildCount() == 0) return;
        View v = lastMachine == null ? null : machineRow.findViewWithTag(lastMachine);
        (v != null ? v : machineRow.getChildAt(0)).requestFocus();
    }

    private static String lastMachine;

    private int tileWidth() { return (getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 128) - Ui.dp(this, 36)) / 4; }

    // ---- machines -------------------------------------------------------------------------------

    /** The Sparks and NAS in one row of four; one quiet line when the hub sent none. True if rebuilt. */
    private boolean renderMachines(Live.Mission m, boolean fresh) {
        StringBuilder names = new StringBuilder();
        for (Live.Machine x : m.infra) names.append(x.name).append('\n');
        boolean rebuild = !names.toString().equals(shownMachines);
        if (rebuild) buildMachines(m);
        shownMachines = names.toString();
        for (Live.Machine x : m.infra) fillMachine(machineTiles.get(x.name), x, fresh);
        return rebuild;
    }

    private void buildMachines(Live.Mission m) {
        machines.removeAllViews();
        machineTiles.clear();
        machineRow = null;
        if (m.infra.isEmpty()) {
            machines.addView(Ui.text(this, "Machines · no answer from the hub", 9.5f, t.dim, 300), Ui.lpTop(this, -2, -2, 2));
            return;
        }
        machines.addView(Ui.eyebrow(this, t, "Machines"));
        machineRow = Ui.hbox(this);
        machineRow.setClipChildren(false);
        machines.addView(machineRow, Ui.lpTop(this, -1, -2, 8));
        final int n = m.infra.size();
        int w = tileWidth();
        for (int i = 0; i < n; i++) {
            Live.Machine x = m.infra.get(i);
            MachineTile mt = machineTile(x);
            machineTiles.put(x.name, mt);
            final int idx = i;
            // never wrap; the bottom row of the screen stops the D-pad going down
            mt.plate.setOnKeyListener((v, key, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT && idx == 0) return true;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT && idx == n - 1) return true;
                if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_UP) { focusTag(lastAgentName); return true; }
                if (key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_DOWN) return true;
                return key == KeyEvent.KEYCODE_DPAD_DOWN;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, Ui.dp(this, 106));
            lp.rightMargin = i < n - 1 ? Ui.dp(this, 12) : 0;
            lp.bottomMargin = Ui.dp(this, 4);
            machineRow.addView(mt.plate, lp);
        }
    }

    private MachineTile machineTile(Live.Machine x) {
        MachineTile mt = new MachineTile();
        Plate p = Plate.of(this, t, Plate.Kind.TILE);
        mt.plate = p;
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(Ui.dp(this, 15), Ui.dp(this, 11), Ui.dp(this, 14), Ui.dp(this, 10));
        p.setTag("machine:" + x.name);   // not an agent name, so the agent grid never claims it
        p.onFocus((pl, has) -> {
            if (!has) return;
            lastFocus = lastMachine = "machine:" + x.name;
            if (editing) editFoot();
            else foot("OK", "machine details", "CH ▲", "agents", "BACK", "command center");
        });
        p.setOnClickListener(v -> {
            if (editing) { cardOptions("machine:" + x.name); return; }
            startActivity(new Intent(this, MachineActivity.class).putExtra(MachineActivity.EXTRA, x.name));
            overridePendingTransition(0, 0);
        });
        mt.name = Ui.single(Ui.text(this, x.shown(), 15, t.text, 600));
        p.addView(mt.name);
        mt.status = Ui.hbox(this);
        p.addView(mt.status, Ui.lpTop(this, -1, -2, 4));
        mt.role = Ui.text(this, "", 9f, t.light ? 0xB30B0F14 : 0xB3F2F4F7, 300);
        mt.role.setMaxLines(x.nas() ? 1 : 2);   // NAS gives its second line to the meter
        mt.role.setEllipsize(android.text.TextUtils.TruncateAt.END);
        p.addView(mt.role, Ui.lpTop(this, -1, -2, 4));
        p.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));
        mt.spec = Ui.single(Ui.text(this, "", 8f, t.dim, 400));
        p.addView(mt.spec);
        if (x.nas()) {   // the free space from the hub's reading, as a meter: filled is used
            mt.meter = Ui.hbox(this);
            mt.meter.setBackground(Ui.pill(this, t.light ? 0x1F0B0F14 : 0x24F2F4F7));
            mt.used = new View(this);
            mt.meter.addView(mt.used, new LinearLayout.LayoutParams(0, -1, 0f));
            mt.meter.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1f));
            p.addView(mt.meter, Ui.lpTop(this, -1, Ui.dp(this, 4), 5));
        }
        return mt;
    }

    /** Rewrite a machine tile's words in place (no relayout of the row, no focus change). */
    private void fillMachine(MachineTile mt, Live.Machine x, boolean fresh) {
        if (mt == null) return;
        String st = fresh ? x.state() : Ui.Status.IDLE;
        mt.status.removeAllViews();
        mt.status.addView(dotWord(st, x.why.isEmpty() ? "No reading" : x.why, 9f));
        mt.name.setText(x.shown());
        mt.role.setText(x.role);
        mt.spec.setText(x.spec());
        if (mt.meter != null) {
            double[] sp = x.space();
            mt.meter.setVisibility(sp == null ? View.GONE : View.VISIBLE);
            if (sp != null) {
                float used = (float) Math.max(0, Math.min(1, 1 - sp[0] / sp[1]));
                mt.used.setBackground(Ui.pill(this, fresh ? t.state(x.state()) : t.dim));
                ((LinearLayout.LayoutParams) mt.used.getLayoutParams()).weight = used;
                ((LinearLayout.LayoutParams) mt.meter.getChildAt(1).getLayoutParams()).weight = 1 - used;
                mt.meter.requestLayout();
            }
        }
    }

    private void addCount(String state, String word, boolean fresh) {
        LinearLayout d = dotWord(fresh ? state : Ui.Status.IDLE, word, 10);
        LinearLayout.LayoutParams lp = Ui.lp(-2, -2);
        lp.rightMargin = Ui.dp(this, 15);
        summary.addView(d, lp);
    }

    private static String lastAgentName;

    /** compact: three rows on screen, so the role is one line and the age lives in the header only. */
    private AgentTile tile(final Live.Agent a, boolean compact) {
        AgentTile at = new AgentTile();
        Plate p = Plate.of(this, t, Plate.Kind.TILE);
        at.plate = p;
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(Ui.dp(this, 15), Ui.dp(this, compact ? 10 : 14), Ui.dp(this, 14), Ui.dp(this, compact ? 9.5f : 13));
        p.setTag(a.name);
        p.onFocus((pl, has) -> {
            if (!has) return;
            lastFocus = lastAgentName = a.name;
            if (editing) editFoot();
            else if (machineRow != null) foot("OK", "open agent", "CH ▼", "machines", "BACK", "command center");
            else foot("OK", "open agent", "BACK", "command center");
        });
        p.setOnClickListener(v -> {
            if (editing) { cardOptions(a.name); return; }
            Intent i = new Intent(this, AgentActivity.class);
            i.putExtra(AgentActivity.EXTRA_NAME, a.name);
            startActivity(i);
            overridePendingTransition(0, 0);
        });

        LinearLayout top = Ui.hbox(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        at.name = Ui.single(Ui.text(this, a.shown(), 17, t.text, 600));
        top.addView(at.name, new LinearLayout.LayoutParams(0, -2, 1f));
        at.dot = Ui.hbox(this);
        top.addView(at.dot);
        p.addView(top, Ui.lp(-1, -2));

        // the hub's reason, in the state's colour: why it's orange or grey, not just that it is
        at.why = Ui.single(Ui.text(this, "", 9f, t.dim, 500));
        p.addView(at.why, Ui.lpTop(this, -1, -2, 3));

        at.role = Ui.text(this, "", 9.5f, t.light ? 0xB30B0F14 : 0xB3F2F4F7, 300);
        at.role.setMaxLines(compact ? 1 : 2);
        at.role.setEllipsize(android.text.TextUtils.TruncateAt.END);
        p.addView(at.role, Ui.lpTop(this, -1, -2, 4));

        View spacer = new View(this);
        p.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        at.host = Ui.single(Ui.text(this, "", 8.5f, t.dim, 400));
        p.addView(at.host);
        if (!compact) {
            at.age = Ui.single(Ui.text(this, "", 8f, (t.dim & 0x00FFFFFF) | 0xB3000000, 300));
            p.addView(at.age, Ui.lpTop(this, -1, -2, 2));
        }
        return at;
    }

    /** Rewrite an agent tile's words in place (no relayout of the grid, no focus change). */
    private void fillAgent(AgentTile at, Live.Agent a, boolean fresh, Live.Mission m) {
        if (at == null) return;
        boolean planned = Ui.Status.IDLE.equals(a.state());
        at.name.setText(a.shown());
        at.name.setTextColor(planned ? (t.light ? 0xC70B0F14 : 0xC7F2F4F7) : t.text);
        at.dot.removeAllViews();
        at.dot.addView(dotWord(fresh ? a.state() : Ui.Status.IDLE, a.word(), 9f));
        at.why.setText(a.label());
        at.why.setTextColor(fresh ? t.stateText(a.state()) : t.dim);
        at.role.setText(a.note);
        at.host.setText(hostLabel(a.host));
        if (at.age != null) at.age.setText(fresh ? "reported " + Live.ago(m.at) : "last reading " + Live.ago(m.at));
    }

    // ---- edit mode ---------------------------------------------------------------------------------

    private void editFoot() {
        if (liftedTag != null) foot("◀ ▶ ▲ ▼", "move", "OK", "drop", "BACK", "put back");
        else foot("OK", "move · rename · note", "▲", "done button", "BACK", "done");
    }

    private void startEditing() {
        Live.Mission m = Live.mission();
        if (m.agents.isEmpty() && m.infra.isEmpty()) { note("Nothing to arrange until Mission Control answers"); return; }
        agentOrder.clear(); machineOrder.clear(); edits.clear();
        for (Live.Agent a : m.agents) {
            agentOrder.add(a.name);
            edits.put("a:" + a.name, new String[]{a.label.equals(a.defaultLabel) ? "" : a.label, a.note.equals(a.defaultNote) ? "" : a.note});
        }
        for (Live.Machine x : m.infra) {
            machineOrder.add(x.name);
            edits.put("m:" + x.name, new String[]{x.label.equals(x.defaultLabel) ? "" : x.label, x.role.equals(x.defaultNote) ? "" : x.role});
        }
        editing = true; dirty = false; liftedTag = null;
        editLabel.setText("Done");
        editButton.setKind(t, Plate.Kind.PRIMARY);
        editLabel.setTextColor(t.onGold);
        render(Live.mission());
        focusTag(lastFocus);
        editFoot();
    }

    /** Leave edit mode; save = send the layout to Hub first (and stay in edit mode if that fails). */
    private void finishEditing(boolean save) {
        if (liftedTag != null) dropBack();
        if (save && dirty) {
            final org.json.JSONObject body = layoutJson();
            feed.setText("Saving on Hub…");
            new Thread(() -> {
                String why = Live.saveLayoutBlocking(getApplicationContext(), body);
                main.post(() -> {
                    if (why != null) { note("Not saved · " + why); render(Live.mission()); return; }
                    note("Layout saved on Hub");
                    leaveEditing();
                    refresh();
                });
            }, "mills-layout").start();
            return;
        }
        leaveEditing();
    }

    private void leaveEditing() {
        editing = false; dirty = false; liftedTag = null;
        editLabel.setText("Edit layout");
        editButton.setKind(t, Plate.Kind.BUTTON);
        editLabel.setTextColor(t.text);
        shownAgents = shownMachines = null;      // rebuild in the hub's (now saved) order
        render(Live.mission());
    }

    private org.json.JSONObject layoutJson() {
        org.json.JSONObject o = new org.json.JSONObject();
        try {
            o.put("agents", group("a:", agentOrder));
            o.put("machines", group("m:", machineOrder));
        } catch (org.json.JSONException ignored) {}
        return o;
    }

    private org.json.JSONArray group(String prefix, java.util.List<String> order) throws org.json.JSONException {
        org.json.JSONArray a = new org.json.JSONArray();
        for (String n : order) {
            String[] e = edits.get(prefix + n);
            org.json.JSONObject r = new org.json.JSONObject().put("name", n);
            if (e != null) { r.put("label", e[0]); r.put("note", e[1]); }
            a.put(r);
        }
        return a;
    }

    /** A copy of the hub's reading in the edit order, with the names and notes being edited. */
    private Live.Mission arrangedForEdit(Live.Mission src) {
        Live.Mission m = new Live.Mission();
        m.ok = src.ok; m.at = src.at; m.host = src.host;
        java.util.Map<String, Live.Agent> as = new java.util.LinkedHashMap<>();
        for (Live.Agent a : src.agents) as.put(a.name, a.copy());
        for (String n : agentOrder) { Live.Agent a = as.remove(n); if (a != null) m.agents.add(a); }
        m.agents.addAll(as.values());
        java.util.Map<String, Live.Machine> ms = new java.util.LinkedHashMap<>();
        for (Live.Machine x : src.infra) ms.put(x.name, x.copy());
        for (String n : machineOrder) { Live.Machine x = ms.remove(n); if (x != null) m.infra.add(x); }
        m.infra.addAll(ms.values());
        for (Live.Agent a : m.agents) {
            String[] e = edits.get("a:" + a.name);
            if (e == null) continue;
            a.label = e[0].isEmpty() ? a.defaultLabel : e[0];
            a.note = e[1].isEmpty() ? a.defaultNote : e[1];
        }
        for (Live.Machine x : m.infra) {
            String[] e = edits.get("m:" + x.name);
            if (e == null) continue;
            x.label = e[0].isEmpty() ? x.defaultLabel : e[0];
            x.role = e[1].isEmpty() ? x.defaultNote : e[1];
        }
        return m;
    }

    private void cardOptions(final String tag) {
        final boolean machine = tag.startsWith("machine:");
        final String name = machine ? tag.substring(8) : tag;
        final String key = (machine ? "m:" : "a:") + name;
        String[] e = edits.get(key);
        final String[] cur = e != null ? e : new String[]{"", ""};
        String[] items = {"Move", "Rename", "Edit note", "Reset name and note"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(items, (d, w) -> {
                    if (w == 0) lift(tag);
                    else if (w == 1) ask("Name on the card", cur[0].isEmpty() ? shownText(tag, 0) : cur[0], 24, v -> { cur[0] = v.equals(name) ? "" : v; edits.put(key, cur); changed(); });
                    else if (w == 2) ask("Note on the card", cur[1].isEmpty() ? shownText(tag, 1) : cur[1], 80, v -> { cur[1] = v; edits.put(key, cur); changed(); });
                    else { edits.put(key, new String[]{"", ""}); changed(); }
                })
                .show();
    }

    /** What the card shows now: 0 the name, 1 the note. */
    private String shownText(String tag, int which) {
        Live.Mission m = Live.mission();
        if (tag.startsWith("machine:")) {
            Live.Machine x = m.machine(tag.substring(8));
            return x == null ? "" : which == 0 ? x.name : x.defaultNote;
        }
        Live.Agent a = m.find(tag);
        return a == null ? "" : which == 0 ? a.name : a.defaultNote;
    }

    interface Got { void got(String v); }

    private void ask(String title, String value, int max, Got cb) {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setSingleLine(true);
        in.setHint(value);        // the TV keyboard ignores a selection, so start empty: typing never appends
        in.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Type the new one. Reset on the card's menu puts back the default. Saved on Hub when you press Done.")
                .setView(in)
                .setPositiveButton("OK", (d, w) -> {
                    String v = in.getText().toString().trim().replaceAll("\\s+", " ");
                    if (!v.isEmpty()) cb.got(v);
                })
                .setNegativeButton("Cancel", null)
                .create();
        // the keyboard's Done means OK
        dlg.setOnShowListener(d -> in.requestFocus());
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        in.setOnEditorActionListener((v, id, ev) -> {
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        dlg.show();
    }

    private void changed() { dirty = true; render(Live.mission()); editFoot(); }

    private void lift(String tag) {
        liftedTag = tag;
        dirtyBeforeLift = dirty;
        liftedFrom = new java.util.ArrayList<>(tag.startsWith("machine:") ? machineOrder : agentOrder);
        View v = findTile(tag);
        if (v instanceof Plate) ((Plate) v).setLifted(true);
        editFoot();
    }

    /** Carry the lifted card one place: dx ±1 along its group, dy ±1 a grid row (agents only). */
    private void moveLifted(int dx, int dy) {
        boolean machine = liftedTag.startsWith("machine:");
        java.util.List<String> order = machine ? machineOrder : agentOrder;
        String name = machine ? liftedTag.substring(8) : liftedTag;
        int i = order.indexOf(name);
        if (i < 0) return;
        int j = machine ? i + dx : i + dx + dy * 4;
        if (machine && dy != 0) return;
        if (!machine && dx != 0 && (j / 4 != i / 4)) return;   // ◀ ▶ stay on the row; ▲ ▼ change rows
        if (j < 0 || j >= order.size()) return;
        order.remove(i);
        order.add(j, name);
        dirty = true;
        render(Live.mission());
    }

    private void drop() {
        View v = findTile(liftedTag);
        if (v instanceof Plate) ((Plate) v).setLifted(false);
        liftedTag = null; liftedFrom = null;
        render(Live.mission());
        editFoot();
    }

    private void dropBack() {
        if (liftedFrom != null) {
            java.util.List<String> order = liftedTag.startsWith("machine:") ? machineOrder : agentOrder;
            order.clear(); order.addAll(liftedFrom);
            dirty = dirtyBeforeLift;
        }
        drop();
    }

    private void note(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && liftedTag != null) {
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_LEFT:  moveLifted(-1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: moveLifted(1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_UP:    moveLifted(0, -1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:  moveLifted(0, 1); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    drop(); return true;
                case KeyEvent.KEYCODE_BACK: dropBack(); return true;
                default: return true;      // nothing else while a card is in the air
            }
        }
        if (liftedTag != null && e.getAction() == KeyEvent.ACTION_UP) return true;
        if (e.getAction() == KeyEvent.ACTION_DOWN && k == KeyEvent.KEYCODE_MENU) {
            if (editing) finishEditing(true); else startEditing();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (!editing) { super.onBackPressed(); return; }
        if (!dirty) { finishEditing(false); return; }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Save this layout?")
                .setItems(new String[]{"Save on Hub", "Discard changes", "Keep editing"}, (d, w) -> {
                    if (w == 0) finishEditing(true);
                    else if (w == 1) finishEditing(false);
                })
                .show();
    }

    static String hostLabel(String host) {
        if (host == null || host.isEmpty() || host.equals("—")) return "no machine assigned";
        // Exactly as Mission Control reports it: ".10" is on the agents' own network, not this LAN.
        return host.startsWith(".") ? "host " + host : host;
    }
}
