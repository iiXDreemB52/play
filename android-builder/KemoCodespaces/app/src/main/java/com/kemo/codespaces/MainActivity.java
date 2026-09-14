package com.kemo.codespaces;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final String PREFS = "kemo_codespaces";
    private static final String PREF_TOKEN = "github_token_encrypted";
    private static final String KEY_ALIAS = "kemo_codespaces_token_key";
    private static final String API_VERSION = "2026-03-10";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<CodespaceItem> codespaces = new ArrayList<>();

    private LinearLayout projectsContainer;
    private ProgressBar progress;
    private TextView statusText;
    private TextView tokenStateText;
    private Button refreshButton;
    private Button tokenButton;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(7, 11, 24));
        getWindow().setNavigationBarColor(Color.rgb(7, 11, 24));
        buildUi();
        refreshTokenState();
        if (!loadToken().isEmpty()) loadCodespaces();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF070B18);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(30), dp(18), dp(30));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView logo = text("K", 30, Color.WHITE, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(roundRect(28, 0xFF7C3AED, 0, 0));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        logoParams.bottomMargin = dp(14);
        root.addView(logo, logoParams);

        TextView title = text("Kemo Codespaces", 24, Color.WHITE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, wrap());

        TextView subtitle = text("شغّل أي Codespace من جوالك بضغطة واحدة", 14, 0xFF9CA3AF, Typeface.NORMAL);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subParams = wrap();
        subParams.topMargin = dp(6);
        subParams.bottomMargin = dp(20);
        root.addView(subtitle, subParams);

        LinearLayout tokenCard = card();
        tokenCard.addView(text("ربط GitHub", 17, Color.WHITE, Typeface.BOLD), wrap());
        tokenStateText = text("", 13, 0xFF9CA3AF, Typeface.NORMAL);
        LinearLayout.LayoutParams tokenStateParams = wrap();
        tokenStateParams.topMargin = dp(6);
        tokenCard.addView(tokenStateText, tokenStateParams);

        tokenButton = primaryButton("إعداد GitHub Token", 0xFF312E81);
        LinearLayout.LayoutParams tokenButtonParams = matchWidth(dp(46));
        tokenButtonParams.topMargin = dp(12);
        tokenCard.addView(tokenButton, tokenButtonParams);
        tokenButton.setOnClickListener(v -> showTokenDialog());

        Button githubButton = outlineButton("فتح صفحة إنشاء Fine-grained Token");
        LinearLayout.LayoutParams githubParams = matchWidth(dp(42));
        githubParams.topMargin = dp(8);
        tokenCard.addView(githubButton, githubParams);
        githubButton.setOnClickListener(v -> openExternal("https://github.com/settings/personal-access-tokens/new"));

        LinearLayout.LayoutParams tokenCardParams = matchWidthWrap();
        tokenCardParams.bottomMargin = dp(14);
        root.addView(tokenCard, tokenCardParams);

        LinearLayout listCard = card();
        LinearLayout headingRow = new LinearLayout(this);
        headingRow.setOrientation(LinearLayout.HORIZONTAL);
        headingRow.setGravity(Gravity.CENTER_VERTICAL);
        headingRow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView listTitle = text("Codespaces", 18, Color.WHITE, Typeface.BOLD);
        LinearLayout.LayoutParams listTitleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headingRow.addView(listTitle, listTitleParams);

        refreshButton = smallButton("تحديث");
        headingRow.addView(refreshButton, new LinearLayout.LayoutParams(dp(82), dp(40)));
        refreshButton.setOnClickListener(v -> loadCodespaces());
        listCard.addView(headingRow, matchWidthWrap());

        TextView help = text("التطبيق يكتشف مشاريعك ومنافذها تلقائيًا من devcontainer.json. إذا ما لقى المنفذ، يطلبه منك مرة واحدة ويحفظه.", 12, 0xFF9CA3AF, Typeface.NORMAL);
        help.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams helpParams = matchWidthWrap();
        helpParams.topMargin = dp(8);
        helpParams.bottomMargin = dp(12);
        listCard.addView(help, helpParams);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = dp(6);
        listCard.addView(progress, progressParams);

        statusText = text("جاهز", 13, 0xFFA7F3D0, Typeface.BOLD);
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = matchWidthWrap();
        statusParams.topMargin = dp(6);
        statusParams.bottomMargin = dp(12);
        listCard.addView(statusText, statusParams);

        projectsContainer = new LinearLayout(this);
        projectsContainer.setOrientation(LinearLayout.VERTICAL);
        listCard.addView(projectsContainer, matchWidthWrap());

        root.addView(listCard, matchWidthWrap());

        TextView privacy = text("GitHub Token يُحفظ مشفّرًا داخل Android Keystore ولا يتم تضمينه داخل ملف APK.", 12, 0xFF6B7280, Typeface.NORMAL);
        privacy.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams privacyParams = matchWidthWrap();
        privacyParams.topMargin = dp(18);
        root.addView(privacy, privacyParams);

        TextView version = text("v1.0.0", 11, 0xFF4B5563, Typeface.NORMAL);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams versionParams = wrap();
        versionParams.topMargin = dp(6);
        root.addView(version, versionParams);

        setContentView(scroll);
    }

    private void loadCodespaces() {
        if (busy) return;
        String token = loadToken();
        if (token.isEmpty()) {
            showTokenDialog();
            return;
        }

        setBusy(true, "جاري جلب Codespaces...");
        projectsContainer.removeAllViews();

        executor.execute(() -> {
            try {
                ApiResponse response = request("GET", "https://api.github.com/user/codespaces?per_page=100", token);
                if (response.code == 401 || response.code == 403) {
                    finishWithError("التوكن غير صالح أو ناقص صلاحية Codespaces metadata: Read.");
                    return;
                }
                if (response.code != 200) {
                    finishWithError("GitHub رجّع HTTP " + response.code + " أثناء جلب Codespaces.");
                    return;
                }

                JSONObject root = new JSONObject(response.body);
                JSONArray array = root.optJSONArray("codespaces");
                List<CodespaceItem> found = new ArrayList<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item == null) continue;
                        CodespaceItem cs = parseCodespace(item);
                        if (cs == null) continue;
                        cs.port = getCachedPort(cs.name);
                        if (cs.port <= 0) cs.port = discoverPort(cs, token);
                        if (cs.port > 0) cachePort(cs.name, cs.port);
                        found.add(cs);
                    }
                }

                mainHandler.post(() -> {
                    codespaces.clear();
                    codespaces.addAll(found);
                    renderCodespaces();
                    setBusy(false, found.isEmpty() ? "ما لقيت Codespaces" : "تم العثور على " + found.size() + " Codespace ✅");
                });
            } catch (Exception e) {
                finishWithError("تعذر جلب Codespaces: " + readableError(e));
            }
        });
    }

    private CodespaceItem parseCodespace(JSONObject json) {
        String name = json.optString("name", "").trim();
        if (name.isEmpty()) return null;

        JSONObject repo = json.optJSONObject("repository");
        String repoFullName = repo == null ? "" : repo.optString("full_name", "");
        String repoName = repo == null ? "" : repo.optString("name", "");
        String display = json.optString("display_name", "").trim();
        if (display.isEmpty()) display = repoName.isEmpty() ? name : repoName;

        JSONObject gitStatus = json.optJSONObject("git_status");
        String ref = gitStatus == null ? "" : gitStatus.optString("ref", "");
        String state = json.optString("state", "");

        return new CodespaceItem(name, display, repoFullName, ref, state);
    }

    private int discoverPort(CodespaceItem cs, String token) {
        if (cs.repoFullName.isEmpty()) return 0;
        String[] candidates = new String[]{".devcontainer/devcontainer.json", ".devcontainer.json"};
        for (String path : candidates) {
            try {
                String url = "https://api.github.com/repos/" + cs.repoFullName + "/contents/" + path;
                if (!cs.ref.isEmpty()) url += "?ref=" + URLEncoder.encode(cs.ref, "UTF-8");
                ApiResponse response = request("GET", url, token);
                if (response.code != 200) continue;
                JSONObject body = new JSONObject(response.body);
                String encoded = body.optString("content", "").replace("\n", "").trim();
                if (encoded.isEmpty()) continue;
                String decoded = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                JSONObject config = new JSONObject(decoded);

                JSONArray forwardPorts = config.optJSONArray("forwardPorts");
                if (forwardPorts != null) {
                    for (int i = 0; i < forwardPorts.length(); i++) {
                        Object value = forwardPorts.opt(i);
                        int port = parsePort(value);
                        if (port > 0) return port;
                    }
                }

                JSONObject attrs = config.optJSONObject("portsAttributes");
                if (attrs != null) {
                    JSONArray names = attrs.names();
                    if (names != null) {
                        for (int i = 0; i < names.length(); i++) {
                            int port = parsePort(names.optString(i));
                            if (port > 0) return port;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private int parsePort(Object value) {
        if (value == null) return 0;
        try {
            int port;
            if (value instanceof Number) port = ((Number) value).intValue();
            else {
                String s = String.valueOf(value).trim();
                int colon = s.indexOf(':');
                if (colon >= 0) s = s.substring(0, colon);
                port = Integer.parseInt(s);
            }
            return port > 0 && port <= 65535 ? port : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void renderCodespaces() {
        projectsContainer.removeAllViews();
        if (codespaces.isEmpty()) {
            TextView empty = text("ما فيه Codespaces ظاهرة لهذا التوكن.", 13, 0xFF9CA3AF, Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            projectsContainer.addView(empty, matchWidthWrap());
            return;
        }

        for (CodespaceItem cs : codespaces) {
            LinearLayout project = new LinearLayout(this);
            project.setOrientation(LinearLayout.VERTICAL);
            project.setPadding(dp(14), dp(14), dp(14), dp(14));
            project.setBackground(roundRect(16, 0xFF0B1220, 1, 0xFF263449));

            TextView name = text(cs.displayName, 16, Color.WHITE, Typeface.BOLD);
            project.addView(name, matchWidthWrap());

            String repoLine = cs.repoFullName.isEmpty() ? cs.name : cs.repoFullName;
            TextView repo = text(repoLine, 12, 0xFF94A3B8, Typeface.NORMAL);
            repo.setTextDirection(View.TEXT_DIRECTION_LTR);
            LinearLayout.LayoutParams repoParams = matchWidthWrap();
            repoParams.topMargin = dp(4);
            project.addView(repo, repoParams);

            String infoText = "الحالة: " + (cs.state.isEmpty() ? "غير معروفة" : cs.state)
                    + "   •   المنفذ: " + (cs.port > 0 ? String.valueOf(cs.port) : "غير مكتشف");
            TextView info = text(infoText, 12, cs.port > 0 ? 0xFFA7F3D0 : 0xFFFDE68A, Typeface.NORMAL);
            LinearLayout.LayoutParams infoParams = matchWidthWrap();
            infoParams.topMargin = dp(5);
            project.addView(info, infoParams);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            actions.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            LinearLayout.LayoutParams actionsParams = matchWidthWrap();
            actionsParams.topMargin = dp(12);

            Button start = primaryButton("▶ تشغيل", 0xFF7C3AED);
            LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
            actions.addView(start, startParams);
            start.setOnClickListener(v -> ensurePortThenStart(cs));

            Button port = smallButton("المنفذ");
            LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(dp(86), dp(46));
            portParams.setMarginStart(dp(8));
            actions.addView(port, portParams);
            port.setOnClickListener(v -> askPort(cs, false));

            project.addView(actions, actionsParams);

            LinearLayout.LayoutParams projectParams = matchWidthWrap();
            projectParams.bottomMargin = dp(10);
            projectsContainer.addView(project, projectParams);
        }
    }

    private void ensurePortThenStart(CodespaceItem cs) {
        if (cs.port <= 0) {
            askPort(cs, true);
            return;
        }
        startCodespace(cs);
    }

    private void askPort(CodespaceItem cs, boolean startAfterSave) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setHint("مثال: 3000 أو 10000");
        if (cs.port > 0) input.setText(String.valueOf(cs.port));
        int pad = dp(22);
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(pad, 0, pad, 0);
        holder.addView(input, matchWidth(dp(54)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("منفذ " + cs.displayName)
                .setMessage("إذا ما قدر التطبيق يكتشف المنفذ تلقائيًا، اكتبه مرة واحدة.")
                .setView(holder)
                .setPositiveButton("حفظ", null)
                .setNegativeButton("إلغاء", null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int value = parsePort(input.getText().toString());
            if (value <= 0) {
                input.setError("أدخل منفذ صحيح من 1 إلى 65535");
                return;
            }
            cs.port = value;
            cachePort(cs.name, value);
            renderCodespaces();
            dialog.dismiss();
            if (startAfterSave) startCodespace(cs);
        }));
        dialog.show();
    }

    private void startCodespace(CodespaceItem cs) {
        if (busy) return;
        String token = loadToken();
        if (token.isEmpty()) {
            showTokenDialog();
            return;
        }

        setBusy(true, "جاري تشغيل " + cs.displayName + "...");
        executor.execute(() -> {
            try {
                String api = "https://api.github.com/user/codespaces/" + cs.name;
                ApiResponse start = request("POST", api + "/start", token);
                if (start.code == 401 || start.code == 403) {
                    finishWithError("التوكن ناقص صلاحية Codespaces lifecycle admin: Write.");
                    return;
                }
                if (start.code == 404) {
                    finishWithError("ما لقيت Codespace هذا في حسابك.");
                    return;
                }

                updateStatus("تم إرسال أمر التشغيل… بانتظار الجاهزية");
                for (int attempt = 0; attempt < 75; attempt++) {
                    if (Thread.currentThread().isInterrupted()) return;
                    ApiResponse response = request("GET", api, token);
                    if (response.code == 401 || response.code == 403) {
                        finishWithError("التوكن ناقص صلاحية Codespaces metadata: Read.");
                        return;
                    }
                    if (response.code == 200) {
                        JSONObject body = new JSONObject(response.body);
                        String state = body.optString("state", "");
                        if ("Available".equalsIgnoreCase(state)) {
                            String preview = "https://" + cs.name + "-" + cs.port + ".app.github.dev/";
                            cs.state = "Available";
                            mainHandler.post(() -> {
                                setBusy(false, cs.displayName + " جاهز ✅");
                                renderCodespaces();
                                openExternal(preview);
                            });
                            return;
                        }
                        if ("Failed".equalsIgnoreCase(state) || "Unavailable".equalsIgnoreCase(state)) {
                            finishWithError("Codespace دخل حالة " + state + ".");
                            return;
                        }
                        updateStatus("الحالة: " + (state.isEmpty() ? "جاري التجهيز" : state));
                    }
                    Thread.sleep(2000);
                }
                finishWithError("استغرق التشغيل أكثر من دقيقتين ونصف. جرّب مرة ثانية.");
            } catch (Exception e) {
                finishWithError("تعذر التشغيل: " + readableError(e));
            }
        });
    }

    private ApiResponse request(String method, String urlString, String token) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION);
            connection.setRequestProperty("User-Agent", "Kemo-Codespaces/1.0");
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
            return new ApiResponse(code, readStream(stream));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readStream(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private void showTokenDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(6), dp(22), 0);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView help = text(
                "أنشئ Fine-grained Personal Access Token وحدد المستودعات التي فيها Codespaces. الصلاحيات المطلوبة:\n\n• Codespaces lifecycle admin: Write\n• Codespaces metadata: Read\n• Contents: Read\n\nالتوكن يبقى داخل جهازك فقط.",
                13,
                0xFF374151,
                Typeface.NORMAL
        );
        help.setLineSpacing(0, 1.15f);
        box.addView(help, matchWidthWrap());

        EditText input = new EditText(this);
        input.setHint("github_pat_...");
        input.setSingleLine(true);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams inputParams = matchWidth(dp(54));
        inputParams.topMargin = dp(14);
        box.addView(input, inputParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("GitHub Token")
                .setView(box)
                .setPositiveButton("حفظ", null)
                .setNegativeButton("إلغاء", null)
                .setNeutralButton("حذف المحفوظ", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = input.getText().toString().trim();
                if (value.isEmpty()) {
                    input.setError("أدخل التوكن");
                    return;
                }
                try {
                    saveToken(value);
                    input.setText("");
                    refreshTokenState();
                    dialog.dismiss();
                    Toast.makeText(this, "تم حفظ التوكن بشكل مشفّر ✅", Toast.LENGTH_SHORT).show();
                    loadCodespaces();
                } catch (Exception e) {
                    Toast.makeText(this, "تعذر حفظ التوكن", Toast.LENGTH_LONG).show();
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                clearToken();
                codespaces.clear();
                renderCodespaces();
                refreshTokenState();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void refreshTokenState() {
        if (loadToken().isEmpty()) {
            tokenStateText.setText("غير مربوط — أضف Token مرة واحدة");
            tokenStateText.setTextColor(0xFFFCA5A5);
            tokenButton.setText("إعداد GitHub Token");
        } else {
            tokenStateText.setText("مربوط ومحفوظ بشكل مشفّر ✅");
            tokenStateText.setTextColor(0xFFA7F3D0);
            tokenButton.setText("تغيير GitHub Token");
        }
    }

    private void saveToken(String token) throws Exception {
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_TOKEN, value).apply();
    }

    private String loadToken() {
        try {
            String value = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_TOKEN, "");
            if (value == null || value.isEmpty() || !value.contains(".")) return "";
            String[] parts = value.split("\\.", 2);
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private void clearToken() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PREF_TOKEN).apply();
    }

    private void cachePort(String codespaceName, int port) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("port:" + codespaceName, port).apply();
    }

    private int getCachedPort(String codespaceName) {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("port:" + codespaceName, 0);
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        refreshButton.setEnabled(!value);
        tokenButton.setEnabled(!value);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        statusText.setText(message);
        statusText.setTextColor(value ? 0xFFFDE68A : 0xFFA7F3D0);
    }

    private void updateStatus(String message) {
        mainHandler.post(() -> statusText.setText(message));
    }

    private void finishWithError(String message) {
        mainHandler.post(() -> {
            setBusy(false, "تعذر التنفيذ");
            statusText.setTextColor(0xFFFCA5A5);
            new AlertDialog.Builder(this)
                    .setTitle("حدث خطأ")
                    .setMessage(message)
                    .setPositiveButton("حسنًا", null)
                    .show();
        });
    }

    private String readableError(Exception e) {
        String text = e.getMessage();
        return text == null || text.trim().isEmpty() ? e.getClass().getSimpleName() : text;
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "ما لقيت متصفح لفتح الرابط", Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundRect(20, 0xFF111827, 1, 0xFF273449));
        return card;
    }

    private Button primaryButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setBackground(roundRect(14, color, 0, 0));
        return button;
    }

    private Button outlineButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(0xFFC4B5FD);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackground(roundRect(14, 0x00111827, 1, 0xFF6D5BD0));
        return button;
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(0xFFE5E7EB);
        button.setTextSize(12);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(roundRect(12, 0xFF1F2937, 1, 0xFF374151));
        return button;
    }

    private TextView text(String value, int size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setTextDirection(View.TEXT_DIRECTION_RTL);
        return view;
    }

    private GradientDrawable roundRect(int radius, int fill, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (strokeWidth > 0) drawable.setStroke(dp(strokeWidth), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWidthWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWidth(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class CodespaceItem {
        final String name;
        final String displayName;
        final String repoFullName;
        final String ref;
        String state;
        int port;

        CodespaceItem(String name, String displayName, String repoFullName, String ref, String state) {
            this.name = name;
            this.displayName = displayName;
            this.repoFullName = repoFullName;
            this.ref = ref;
            this.state = state;
        }
    }

    private static final class ApiResponse {
        final int code;
        final String body;
        ApiResponse(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
