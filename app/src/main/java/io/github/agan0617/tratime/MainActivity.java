package io.github.agan0617.tratime;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 查台鐵某兩站之間、今天某個時間之後的班次。
 * 資料：交通部 TDX 台鐵每日時刻表（OD 查詢），不帶金鑰，同一天同一段只連一次（存在 prefs）。
 */
public class MainActivity extends Activity {
    private static final String API = "https://tdx.transportdata.tw/api/basic/v3/Rail/TRA/DailyTrainTimetable/OD/%s/to/%s/%s?%%24format=JSON";
    private static final String DEFAULT_FROM = "0960"; // 汐止
    private static final String DEFAULT_TO = "1000";   // 臺北

    private final List<String[]> stations = new ArrayList<>(); // {id, name}
    private final List<Train> trains = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private String fromId = DEFAULT_FROM, toId = DEFAULT_TO;
    private LocalTime time;
    private boolean timeSetByUser;
    private int querySeq;

    private TextView fromView, toView, timeView, statusView;
    private TrainAdapter adapter;
    private SharedPreferences cache;

    static class Train {
        String no, type, head, dep, arr;
        int minutes;
        boolean suspended;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        cache = getSharedPreferences("timetable_cache", MODE_PRIVATE);
        loadStations();

        fromView = findViewById(R.id.from);
        toView = findViewById(R.id.to);
        timeView = findViewById(R.id.time);
        statusView = findViewById(R.id.status);
        ListView list = findViewById(R.id.list);
        adapter = new TrainAdapter();
        list.setAdapter(adapter);

        fromView.setOnClickListener(v -> pickStation(true));
        toView.setOnClickListener(v -> pickStation(false));
        findViewById(R.id.swap).setOnClickListener(v -> {
            String t = fromId; fromId = toId; toId = t;
            query();
        });
        timeView.setOnClickListener(v -> new TimePickerDialog(this, (tp, h, m) -> {
            time = LocalTime.of(h, m);
            timeSetByUser = true;
            query();
        }, time.getHour(), time.getMinute(), true).show());
        timeView.setOnLongClickListener(v -> {   // 長按回到「現在」
            timeSetByUser = false;
            time = nowMinute();
            query();
            return true;
        });
        findViewById(R.id.query).setOnClickListener(v -> query());
        time = nowMinute();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!timeSetByUser) time = nowMinute();   // 沒手動改過就一直跟著現在
        query();
    }

    private static LocalTime nowMinute() {
        return LocalTime.now().withSecond(0).withNano(0);
    }

    private void loadStations() {
        try (InputStream in = getAssets().open("stations.json")) {
            JSONArray a = new JSONArray(readAll(in));
            for (int i = 0; i < a.length(); i++) {
                JSONArray s = a.getJSONArray(i);
                stations.add(new String[]{s.getString(0), s.getString(1)});
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String nameOf(String id) {
        for (String[] s : stations) if (s[0].equals(id)) return s[1];
        return id;
    }

    /** 「台」「臺」都認，打「台北」找得到「臺北」。 */
    private static String norm(String s) {
        return s.replace('台', '臺').trim();
    }

    private void pickStation(boolean isFrom) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        EditText search = new EditText(this);
        search.setHint("輸入站名篩選");
        search.setSingleLine();
        box.addView(search);
        ListView lv = new ListView(this);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (360 * d)));

        List<String[]> shown = new ArrayList<>(stations);
        List<String> names = new ArrayList<>();
        for (String[] s : shown) names.add(s[1]);
        ArrayAdapter<String> aa = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        lv.setAdapter(aa);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(isFrom ? "選擇起站" : "選擇終站")
                .setView(box)
                .setNegativeButton("取消", null)
                .create();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                String q = norm(e.toString());
                shown.clear();
                names.clear();
                for (String[] s : stations) {
                    if (q.isEmpty() || s[1].contains(q)) {
                        shown.add(s);
                        names.add(s[1]);
                    }
                }
                aa.notifyDataSetChanged();
            }
        });
        lv.setOnItemClickListener((p, v, pos, id) -> {
            if (isFrom) fromId = shown.get(pos)[0]; else toId = shown.get(pos)[0];
            dlg.dismiss();
            query();
        });
        dlg.show();
    }

    private void query() {
        fromView.setText(nameOf(fromId));
        toView.setText(nameOf(toId));
        timeView.setText(String.format("%02d:%02d", time.getHour(), time.getMinute()));
        if (fromId.equals(toId)) {
            show(new ArrayList<>(), "起站和終站一樣");
            return;
        }
        final int seq = ++querySeq;
        final String from = fromId, to = toId, date = LocalDate.now().toString();
        final LocalTime after = time;
        final String key = from + "|" + to + "|" + date;
        statusView.setText("查詢中…");
        io.execute(() -> {
            String err = null;
            List<Train> result = new ArrayList<>();
            try {
                // 快取 2 小時：省 TDX 免金鑰額度，又不會錯過當天臨時停駛的更新
                String json = cache.getString(key, null);
                long age = System.currentTimeMillis() - cache.getLong(key + "@t", 0);
                if (json == null || age > 2 * 60 * 60 * 1000L) {
                    try {
                        String fresh = fetch(String.format(API, from, to, date));
                        SharedPreferences.Editor ed = cache.edit();
                        for (String k : cache.getAll().keySet()) if (!k.contains(date)) ed.remove(k); // 只留今天的
                        ed.putString(key, fresh).putLong(key + "@t", System.currentTimeMillis()).apply();
                        json = fresh;
                    } catch (Exception e) {
                        if (json == null) throw e;   // 有舊的就先用舊的
                    }
                }
                result = parse(json, from, to, after);
            } catch (QuotaException e) {
                err = "TDX 免金鑰的每日查詢次數用完了，明天再試";
            } catch (Exception e) {
                err = "查詢失敗：" + e.getMessage();
            }
            final String error = err;
            final List<Train> r = result;
            main.post(() -> {
                if (seq != querySeq) return;   // 已經有更新的查詢
                if (error != null) {
                    show(new ArrayList<>(), error);
                } else {
                    LocalDate today = LocalDate.now();
                    String head = today.getMonthValue() + "/" + today.getDayOfMonth() + " "
                            + timeView.getText() + " 以後，" + nameOf(from) + " → " + nameOf(to);
                    show(r, r.isEmpty() ? head + "：今天沒有班次了" : head + "，共 " + r.size() + " 班");
                }
            });
        });
    }

    private void show(List<Train> list, String status) {
        trains.clear();
        trains.addAll(list);
        adapter.notifyDataSetChanged();
        statusView.setText(status);
    }

    static class QuotaException extends Exception {}

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        // TDX 免金鑰只放行瀏覽器，App 原本的 Dalvik UA 會拿到 401（Ken 2026-09-26 選擇這樣做，不申請金鑰）
        c.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept-Encoding", "identity");
        int code = c.getResponseCode();
        if (code == 429) throw new QuotaException();
        if (code != 200) throw new Exception("HTTP " + code);
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static int toMin(String hhmm) {
        return Integer.parseInt(hhmm.substring(0, 2)) * 60 + Integer.parseInt(hhmm.substring(3, 5));
    }

    static List<Train> parse(String json, String from, String to, LocalTime after) throws Exception {
        JSONArray tt = new JSONObject(json).optJSONArray("TrainTimetables");
        List<Train> out = new ArrayList<>();
        if (tt == null) return out;
        int afterMin = after.getHour() * 60 + after.getMinute();
        for (int i = 0; i < tt.length(); i++) {
            JSONObject o = tt.getJSONObject(i);
            JSONObject info = o.getJSONObject("TrainInfo");
            JSONArray stops = o.getJSONArray("StopTimes");
            String dep = null, arr = null;
            boolean suspended = info.optInt("SuspendedFlag") != 0;
            for (int k = 0; k < stops.length(); k++) {
                JSONObject s = stops.getJSONObject(k);
                String id = s.getString("StationID");
                if (id.equals(from)) {
                    dep = s.getString("DepartureTime");
                    suspended |= s.optInt("SuspendedFlag") != 0;
                } else if (id.equals(to)) {
                    arr = s.getString("ArrivalTime");
                }
            }
            if (dep == null || arr == null || toMin(dep) < afterMin) continue;
            Train t = new Train();
            t.no = info.getString("TrainNo");
            String type = info.getJSONObject("TrainTypeName").getString("Zh_tw");
            int paren = type.indexOf('(');
            t.type = paren > 0 ? type.substring(0, paren) : type;
            t.head = info.optJSONObject("EndingStationName") != null
                    ? info.getJSONObject("EndingStationName").optString("Zh_tw") : "";
            t.dep = dep;
            t.arr = arr;
            int m = toMin(arr) - toMin(dep);
            t.minutes = m < 0 ? m + 24 * 60 : m;
            t.suspended = suspended;
            out.add(t);
        }
        out.sort((a, b) -> Integer.compare(toMin(a.dep), toMin(b.dep)));
        return out;
    }

    private int typeColor(String type) {
        if (type.contains("區間")) return getColor(R.color.type_local);
        if (type.contains("莒光") || type.contains("復興")) return getColor(R.color.type_chukuang);
        return getColor(R.color.type_express);   // 自強、普悠瑪、太魯閣、EMU3000…
    }

    class TrainAdapter extends BaseAdapter {
        @Override public int getCount() { return trains.size(); }
        @Override public Object getItem(int i) { return trains.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(MainActivity.this).inflate(R.layout.row_train, parent, false);
            Train t = trains.get(i);
            ((TextView) v.findViewById(R.id.times)).setText(t.dep + "  →  " + t.arr);
            String dur = t.minutes >= 60 ? (t.minutes / 60) + " 小時 " + (t.minutes % 60) + " 分" : t.minutes + " 分";
            ((TextView) v.findViewById(R.id.info)).setText(
                    (t.suspended ? "⚠ 停駛　" : "") + dur + (t.head.isEmpty() ? "" : "　往" + t.head));
            TextView type = v.findViewById(R.id.type);
            type.setText(t.type);
            type.setTextColor(typeColor(t.type));
            ((TextView) v.findViewById(R.id.trainNo)).setText(t.no + " 次");
            v.setAlpha(t.suspended ? 0.45f : 1f);
            return v;
        }
    }
}
