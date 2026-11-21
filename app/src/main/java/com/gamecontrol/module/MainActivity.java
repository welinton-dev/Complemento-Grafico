package com.gamecontrol.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    /** Um item da lista (nome calculado uma vez só, para a lista rolar leve). */
    private static class ItemApp {
        final ApplicationInfo info;
        final String nome;
        final String pacote;
        final boolean jogo;

        ItemApp(ApplicationInfo info, String nome, boolean jogo) {
            this.info = info;
            this.nome = nome;
            this.pacote = info.packageName;
            this.jogo = jogo;
        }
    }

    private static class Holder {
        ImageView icone;
        TextView nome;
        TextView pacote;
        TextView estado;
        Switch chave;
    }

    private static final int LIMITE_BUSCA = 8; // acima disso a busca aparece
    private static final int PASSO = 5; // o deslizante anda de 5 em 5 %

    private static final String PKG_SHIZUKU = "moe.shizuku.privileged.api";
    private static final int SH_INSTALAR = 0;
    private static final int SH_ABRIR = 1;
    private static final int SH_AUTORIZAR = 2;
    private static final int SH_OK = 3;

    private TextView txtStatus;
    private TextView txtPerfil;
    private TextView txtContador;
    private Button btnShizuku;
    private EditText edtBusca;
    private AppAdapter adapter;

    private final List<ItemApp> todos = new ArrayList<>();
    private final List<ItemApp> visiveis = new ArrayList<>();
    private final Map<String, Drawable> cacheIcones = new HashMap<>();
    private Set<String> ignorados = new HashSet<>();
    private Set<String> extras = new HashSet<>();
    private Map<String, Integer> aplicados = new HashMap<>();
    private boolean mostrarTodos;
    private int geracao = 0;
    private int estadoShizuku = SH_INSTALAR;

    // Atualiza o "Perfil ativo" na hora quando o tile muda o estado (o app pode estar aberto)
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (prefs, chave) ->
                    runOnUiThread(
                            () -> {
                                atualizarPerfilAtivo();
                                if (chave == null
                                        || GerenciadorGrafico.KEY_MODO_ATIVO.equals(chave)) {
                                    atualizarAplicados();
                                }
                            });

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> runOnUiThread(this::aoShizukuMudar);
    private final Shizuku.OnBinderReceivedListener binderRecebido =
            () -> runOnUiThread(this::aoShizukuMudar);
    private final Shizuku.OnBinderDeadListener binderMorto =
            () -> runOnUiThread(this::aoShizukuMudar);

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mostrarTodos = GerenciadorGrafico.lerMostrarTodos(this);

        construirTela();
        carregarApps();

        // Registrar só depois da tela pronta: o listener "sticky" dispara na hora
        Shizuku.addRequestPermissionResultListener(permissionListener);
        Shizuku.addBinderReceivedListenerSticky(binderRecebido);
        Shizuku.addBinderDeadListener(binderMorto);
    }

    @Override
    protected void onStart() {
        super.onStart();
        getSharedPreferences(GerenciadorGrafico.PREFS_NAME, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener);
    }

    @Override
    protected void onStop() {
        getSharedPreferences(GerenciadorGrafico.PREFS_NAME, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        atualizarStatusShizuku();
        atualizarPerfilAtivo();
        atualizarAplicados();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        Shizuku.removeBinderReceivedListener(binderRecebido);
        Shizuku.removeBinderDeadListener(binderMorto);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- montagem da tela

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout novaLinha() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private Button botaoTexto(String texto) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(texto);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams peso() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void construirTela() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        // Linha 1: status do Shizuku + botão contextual + menu ⋮
        LinearLayout linhaStatus = novaLinha();
        txtStatus = new TextView(this);
        txtStatus.setTextSize(15f);
        linhaStatus.addView(txtStatus, peso());

        btnShizuku = botaoTexto(getString(R.string.btn_authorize));
        btnShizuku.setOnClickListener(v -> acaoShizuku());
        linhaStatus.addView(btnShizuku);

        Button btnMenu = botaoTexto("⋮");
        btnMenu.setTextSize(22f);
        btnMenu.setMinWidth(dp(48));
        btnMenu.setOnClickListener(v -> mostrarMenu(v));
        linhaStatus.addView(btnMenu);
        root.addView(linhaStatus);

        // Linha 2: perfil ativo + Restaurar (ação de segurança, sempre à vista)
        LinearLayout linhaPerfil = novaLinha();
        txtPerfil = new TextView(this);
        txtPerfil.setTextSize(14f);
        linhaPerfil.addView(txtPerfil, peso());

        Button btnRestaurar = botaoTexto(getString(R.string.btn_restore));
        btnRestaurar.setOnClickListener(v -> restaurarPadrao());
        linhaPerfil.addView(btnRestaurar);
        root.addView(linhaPerfil);

        // Linha 3: busca (aparece só quando a lista é grande)
        edtBusca = new EditText(this);
        edtBusca.setHint(getString(R.string.search_hint));
        edtBusca.setSingleLine(true);
        edtBusca.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edtBusca.setVisibility(View.GONE);
        edtBusca.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        aplicarFiltro();
                    }
                });
        root.addView(edtBusca);

        // Linha 4: contador
        txtContador = new TextView(this);
        txtContador.setTextSize(14f);
        txtContador.setPadding(dp(4), dp(6), dp(4), dp(2));
        root.addView(txtContador);

        // Linha 5: marcar / desmarcar todos (valem para o que está visível na lista)
        LinearLayout linhaLote = novaLinha();
        Button btnMarcar = botaoTexto(getString(R.string.btn_select_all));
        btnMarcar.setOnClickListener(v -> marcarVisiveis(true));
        Button btnDesmarcar = botaoTexto(getString(R.string.btn_clear_all));
        btnDesmarcar.setOnClickListener(v -> marcarVisiveis(false));
        linhaLote.addView(btnMarcar, peso());
        linhaLote.addView(btnDesmarcar, peso());
        root.addView(linhaLote);

        // Lista: ocupa o resto da tela
        ListView listView = new ListView(this);
        adapter = new AppAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(
                (parent, view, position, id) -> alternar(visiveis.get(position)));
        root.addView(
                listView,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private void mostrarMenu(View ancora) {
        PopupMenu menu = new PopupMenu(this, ancora);
        Menu m = menu.getMenu();
        m.add(Menu.NONE, 1, Menu.NONE, R.string.menu_adjust);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            m.add(Menu.NONE, 2, Menu.NONE, R.string.menu_add_tiles);
        }
        MenuItem itemTodos = m.add(Menu.NONE, 3, Menu.NONE, R.string.menu_show_all);
        itemTodos.setCheckable(true);
        itemTodos.setChecked(mostrarTodos);
        m.add(Menu.NONE, 4, Menu.NONE, R.string.menu_help);
        m.add(Menu.NONE, 5, Menu.NONE, R.string.menu_diagnostics);
        m.add(Menu.NONE, 6, Menu.NONE, R.string.menu_about);

        menu.setOnMenuItemClickListener(
                item -> {
                    switch (item.getItemId()) {
                        case 1:
                            mostrarAjuste();
                            break;
                        case 2:
                            solicitarAdicaoTiles();
                            break;
                        case 3:
                            mostrarTodos = !mostrarTodos;
                            GerenciadorGrafico.salvarMostrarTodos(this, mostrarTodos);
                            carregarApps();
                            break;
                        case 4:
                            mostrarAjuda();
                            break;
                        case 5:
                            mostrarDiagnostico();
                            break;
                        case 6:
                            mostrarSobre();
                            break;
                        default:
                            return false;
                    }
                    return true;
                });
        menu.show();
    }

    // ---------------------------------------------------------------- dados da lista

    private void carregarApps() {
        final int minhaGeracao = ++geracao;
        final boolean listarTodos = mostrarTodos;

        new Thread(
                        () -> {
                            PackageManager pm = getPackageManager();
                            List<ItemApp> itens = new ArrayList<>();
                            for (ApplicationInfo info :
                                    GerenciadorGrafico.listarApps(this, listarTodos)) {
                                itens.add(
                                        new ItemApp(
                                                info,
                                                pm.getApplicationLabel(info).toString(),
                                                GerenciadorGrafico.ehJogo(info)));
                            }
                            Collections.sort(itens, (a, b) -> a.nome.compareToIgnoreCase(b.nome));

                            runOnUiThread(
                                    () -> {
                                        if (isDestroyed() || minhaGeracao != geracao) return;
                                        ignorados = GerenciadorGrafico.jogosIgnorados(this);
                                        extras = GerenciadorGrafico.appsExtras(this);
                                        todos.clear();
                                        todos.addAll(itens);
                                        edtBusca.setVisibility(
                                                (todos.size() > LIMITE_BUSCA || mostrarTodos)
                                                        ? View.VISIBLE
                                                        : View.GONE);
                                        aplicarFiltro();
                                        atualizarAplicados();
                                    });
                        })
                .start();
    }

    private boolean marcado(ItemApp item) {
        return item.jogo ? !ignorados.contains(item.pacote) : extras.contains(item.pacote);
    }

    private void aplicarFiltro() {
        String q = edtBusca.getText().toString().trim().toLowerCase(Locale.getDefault());
        visiveis.clear();
        for (ItemApp item : todos) {
            if (q.isEmpty()
                    || item.nome.toLowerCase(Locale.getDefault()).contains(q)
                    || item.pacote.toLowerCase(Locale.getDefault()).contains(q)) {
                visiveis.add(item);
            }
        }
        adapter.notifyDataSetChanged();
        atualizarContador();
    }

    private void recarregarSelecao() {
        ignorados = GerenciadorGrafico.jogosIgnorados(this);
        extras = GerenciadorGrafico.appsExtras(this);
        adapter.notifyDataSetChanged();
        atualizarContador();
    }

    private void alternar(ItemApp item) {
        GerenciadorGrafico.definirMarcado(this, item.info, !marcado(item));
        recarregarSelecao();
    }

    private void marcarVisiveis(boolean valor) {
        List<ApplicationInfo> infos = new ArrayList<>();
        for (ItemApp item : visiveis) infos.add(item.info);
        GerenciadorGrafico.definirMarcadoEmLote(this, infos, valor);
        recarregarSelecao();
    }

    private void atualizarContador() {
        if (todos.isEmpty()) {
            txtContador.setText(mostrarTodos ? R.string.empty_apps : R.string.empty_games);
            return;
        }
        int alterados = 0;
        for (ItemApp item : todos) if (marcado(item)) alterados++;
        String parte =
                getResources().getQuantityString(R.plurals.will_change, alterados, alterados);
        txtContador.setText(
                getString(
                        mostrarTodos ? R.string.apps_listed : R.string.games_detected,
                        todos.size(),
                        parte));
    }

    // ---------------------------------------------------------------- Shizuku

    private boolean shizukuInstalado() {
        try {
            getPackageManager().getPackageInfo(PKG_SHIZUKU, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void aoShizukuMudar() {
        atualizarStatusShizuku();
        atualizarAplicados();
    }

    /** Pergunta ao sistema quais itens estão com a mudança gravada e mostra o selo na lista. */
    private void atualizarAplicados() {
        GerenciadorGrafico.lerAplicados(
                this,
                mapa -> {
                    if (isDestroyed()) return;
                    aplicados = mapa;
                    if (adapter != null) adapter.notifyDataSetChanged();
                });
    }

    private void atualizarStatusShizuku() {
        if (txtStatus == null || btnShizuku == null) return;

        if (Shizuku.pingBinder()) {
            estadoShizuku =
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                            ? SH_OK
                            : SH_AUTORIZAR;
        } else {
            estadoShizuku = shizukuInstalado() ? SH_ABRIR : SH_INSTALAR;
        }

        switch (estadoShizuku) {
            case SH_OK:
                txtStatus.setText(R.string.shizuku_ok);
                btnShizuku.setVisibility(View.GONE);
                break;
            case SH_AUTORIZAR:
                txtStatus.setText(R.string.shizuku_waiting);
                btnShizuku.setText(R.string.btn_authorize);
                btnShizuku.setVisibility(View.VISIBLE);
                break;
            case SH_ABRIR:
                txtStatus.setText(R.string.shizuku_off);
                btnShizuku.setText(R.string.btn_open_shizuku);
                btnShizuku.setVisibility(View.VISIBLE);
                break;
            default:
                txtStatus.setText(R.string.shizuku_missing);
                btnShizuku.setText(R.string.btn_install);
                btnShizuku.setVisibility(View.VISIBLE);
                break;
        }
    }

    private void acaoShizuku() {
        switch (estadoShizuku) {
            case SH_AUTORIZAR:
                GerenciadorGrafico.executarComPermissao(this, this::atualizarStatusShizuku);
                break;
            case SH_ABRIR:
                Intent abrir = getPackageManager().getLaunchIntentForPackage(PKG_SHIZUKU);
                if (abrir != null) startActivity(abrir);
                break;
            case SH_INSTALAR:
                abrirPaginaShizuku();
                break;
            default:
                break;
        }
    }

    private void abrirPaginaShizuku() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + PKG_SHIZUKU)));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(
                        new Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/RikkaApps/Shizuku/releases")));
            } catch (ActivityNotFoundException e2) {
                // sem loja nem navegador: nada a fazer
            }
        }
    }

    // ---------------------------------------------------------------- perfil ativo / restaurar

    private void atualizarPerfilAtivo() {
        if (txtPerfil == null) return;
        String modo = GerenciadorGrafico.lerModoAtivo(this);
        String nome = getString(R.string.profile_none);
        if (GerenciadorGrafico.MODO_LEVE.equals(modo)) nome = getString(R.string.profile_balanced);
        else if (GerenciadorGrafico.MODO_ULTRA.equals(modo))
            nome = getString(R.string.profile_performance);
        if (!modo.isEmpty()) {
            nome =
                    getString(
                            R.string.profile_with_pct,
                            nome,
                            GerenciadorGrafico.lerPercentual(this, modo));
        }
        txtPerfil.setText(getString(R.string.profile_active, nome));
    }

    private void restaurarPadrao() {
        GerenciadorGrafico.executarComPermissao(
                this,
                () ->
                        GerenciadorGrafico.restaurarTudo(
                                this,
                                sucesso -> {
                                    if (!sucesso) return;
                                    atualizarPerfilAtivo();
                                    GerenciadorGrafico.atualizarTiles(this);
                                }));
    }

    // ---------------------------------------------------------------- ajuste de intensidade

    private static int percentDe(SeekBar sb) {
        return GerenciadorGrafico.PERCENTUAL_MIN + sb.getProgress() * PASSO;
    }

    private static int progressoDe(int percent) {
        return (percent - GerenciadorGrafico.PERCENTUAL_MIN) / PASSO;
    }

    private SeekBar novoSlider(int percentAtual) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(
                (GerenciadorGrafico.PERCENTUAL_MAX - GerenciadorGrafico.PERCENTUAL_MIN) / PASSO);
        sb.setProgress(progressoDe(percentAtual));
        return sb;
    }

    private void mostrarAjuste() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        TextView aviso = new TextView(this);
        aviso.setTextSize(13f);
        aviso.setAlpha(0.75f);
        aviso.setText(R.string.adjust_intro);
        box.addView(aviso);

        TextView lblLeve = new TextView(this);
        lblLeve.setTextSize(16f);
        lblLeve.setPadding(0, dp(16), 0, 0);
        box.addView(lblLeve);
        SeekBar sbLeve =
                novoSlider(GerenciadorGrafico.lerPercentual(this, GerenciadorGrafico.MODO_LEVE));
        box.addView(sbLeve);

        TextView lblUltra = new TextView(this);
        lblUltra.setTextSize(16f);
        lblUltra.setPadding(0, dp(12), 0, 0);
        box.addView(lblUltra);
        SeekBar sbUltra =
                novoSlider(GerenciadorGrafico.lerPercentual(this, GerenciadorGrafico.MODO_ULTRA));
        box.addView(sbUltra);

        Runnable rotulos =
                () -> {
                    lblLeve.setText(getString(R.string.adjust_balanced, percentDe(sbLeve)));
                    lblUltra.setText(getString(R.string.adjust_performance, percentDe(sbUltra)));
                };
        rotulos.run();

        SeekBar.OnSeekBarChangeListener ouvinte =
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int progress, boolean doUsuario) {
                        if (doUsuario) {
                            if (sb == sbLeve && percentDe(sbLeve) < percentDe(sbUltra)) {
                                sbUltra.setProgress(sbLeve.getProgress());
                            } else if (sb == sbUltra && percentDe(sbUltra) > percentDe(sbLeve)) {
                                sbLeve.setProgress(sbUltra.getProgress());
                            }
                        }
                        rotulos.run();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar sb) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar sb) {
                        salvarEReaplicar(percentDe(sbLeve), percentDe(sbUltra));
                    }
                };
        sbLeve.setOnSeekBarChangeListener(ouvinte);
        sbUltra.setOnSeekBarChangeListener(ouvinte);

        TextView lblAvancado = new TextView(this);
        lblAvancado.setText(R.string.advanced);
        lblAvancado.setTextSize(14f);
        lblAvancado.setAlpha(0.75f);
        lblAvancado.setPadding(0, dp(20), 0, 0);
        box.addView(lblAvancado);

        Switch chkReboot = new Switch(this);
        chkReboot.setText(R.string.keep_until_reboot);
        chkReboot.setTextSize(14f);
        chkReboot.setPadding(0, dp(16), 0, dp(4));
        chkReboot.setChecked(GerenciadorGrafico.lerAteReboot(this));
        chkReboot.setOnCheckedChangeListener(
                (btn, marcado) -> {
                    GerenciadorGrafico.salvarAteReboot(this, marcado);
                    // Com um perfil já ativo, vale na hora (sem precisar religar o botão)
                    if (!GerenciadorGrafico.lerModoAtivo(this).isEmpty()) {
                        GerenciadorGrafico.executarComPermissao(
                                this, () -> GerenciadorGrafico.aplicarSync(this, marcado));
                    }
                });
        box.addView(chkReboot);

        Switch chkForcar = new Switch(this);
        chkForcar.setText(R.string.force_perf);
        chkForcar.setTextSize(14f);
        chkForcar.setPadding(0, dp(8), 0, dp(4));
        chkForcar.setChecked(GerenciadorGrafico.lerForcarPerf(this));
        chkForcar.setOnCheckedChangeListener(
                (btn, marcado) -> GerenciadorGrafico.salvarForcarPerf(this, marcado));
        box.addView(chkForcar);

        TextView notaForcar = new TextView(this);
        notaForcar.setTextSize(12f);
        notaForcar.setAlpha(0.7f);
        notaForcar.setText(R.string.force_perf_note);
        box.addView(notaForcar);

        Button btnPadrao = botaoTexto(getString(R.string.adjust_defaults));
        btnPadrao.setOnClickListener(
                v -> {
                    sbLeve.setProgress(progressoDe(GerenciadorGrafico.PADRAO_LEVE));
                    sbUltra.setProgress(progressoDe(GerenciadorGrafico.PADRAO_ULTRA));
                    salvarEReaplicar(percentDe(sbLeve), percentDe(sbUltra));
                });
        box.addView(btnPadrao);

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_adjust)
                .setView(box)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    /** Salva os valores e, se um perfil está ativo, aplica o novo valor na hora. */
    private void salvarEReaplicar(int leve, int ultra) {
        GerenciadorGrafico.salvarPercentuais(this, leve, ultra);
        atualizarPerfilAtivo();
        GerenciadorGrafico.atualizarTiles(this);

        String modo = GerenciadorGrafico.lerModoAtivo(this);
        if (!modo.isEmpty()) {
            String fator = GerenciadorGrafico.fatorDoModo(this, modo);
            GerenciadorGrafico.executarComPermissao(
                    this,
                    () ->
                            GerenciadorGrafico.aplicarPerfil(
                                    this, fator, ok -> atualizarAplicados()));
        }
    }

    // ---------------------------------------------------------------- sobre / suporte

    private String versaoApp() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return getString(R.string.about_version, pi.versionName, pi.getLongVersionCode());
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private TextView novoTextoSobre(LinearLayout box, String texto, float tamanho, int topoDp) {
        TextView tv = new TextView(this);
        tv.setText(texto);
        tv.setTextSize(tamanho);
        tv.setPadding(0, dp(topoDp), 0, 0);
        Linkify.addLinks(tv, Linkify.WEB_URLS); // links abrem no navegador do sistema
        box.addView(tv);
        return tv;
    }

    private void mostrarSobre() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView titulo = novoTextoSobre(box, getString(R.string.app_name), 20f, 0);
        titulo.setTypeface(null, Typeface.BOLD);
        novoTextoSobre(box, versaoApp(), 14f, 2);
        novoTextoSobre(box, getString(R.string.about_description), 14f, 12);

        if (!InfoApp.AUTOR.isEmpty()) {
            novoTextoSobre(box, getString(R.string.about_author, InfoApp.AUTOR), 14f, 12);
        }
        if (!InfoApp.LICENCA.isEmpty()) {
            novoTextoSobre(box, getString(R.string.about_license, InfoApp.LICENCA), 14f, 4);
        }
        if (!InfoApp.URL_REPOSITORIO.isEmpty()) {
            novoTextoSobre(
                    box, getString(R.string.about_source, InfoApp.URL_REPOSITORIO), 14f, 12);
            novoTextoSobre(
                    box,
                    getString(R.string.about_issues, InfoApp.URL_REPOSITORIO + "/issues"),
                    14f,
                    12);
        }

        novoTextoSobre(box, getString(R.string.about_credits), 14f, 16);
        novoTextoSobre(box, getString(R.string.about_privacy), 14f, 16);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle(R.string.menu_about)
                .setView(sv)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.btn_copy_support, (d, w) -> copiarSuporte())
                .show();
    }

    /** Copia versão, aparelho e diagnóstico, para colar num relato de problema. */
    private void copiarSuporte() {
        // Texto técnico em inglês de propósito: facilita para quem for ler o relato
        StringBuilder sb = new StringBuilder();
        sb.append("gameControl support info\n");
        sb.append("App: ").append(versaoApp()).append("\n");
        sb.append("Android: ")
                .append(Build.VERSION.RELEASE)
                .append(" (API ")
                .append(Build.VERSION.SDK_INT)
                .append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("Language: ").append(Locale.getDefault()).append("\n");

        boolean pronto = false;
        try {
            pronto =
                    Shizuku.pingBinder()
                            && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignorado) {
            // sem Shizuku: copia só o básico
        }

        if (pronto) {
            GerenciadorGrafico.diagnostico(
                    this, texto -> copiarParaAreaDeTransferencia(sb + "\n" + texto));
        } else {
            copiarParaAreaDeTransferencia(sb + "\nShizuku: not available (diagnostics skipped)\n");
        }
    }

    private void copiarParaAreaDeTransferencia(String texto) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("gameControl", texto));
        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- ajuda e tiles

    private void mostrarAjuda() {
        String comoAdicionar =
                getString(
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                ? R.string.help_add_13
                                : R.string.help_add_manual);

        String msg =
                getString(
                        R.string.help_message,
                        GerenciadorGrafico.lerPercentual(this, GerenciadorGrafico.MODO_LEVE),
                        GerenciadorGrafico.lerPercentual(this, GerenciadorGrafico.MODO_ULTRA),
                        comoAdicionar);

        new AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(msg)
                .setPositiveButton(R.string.got_it, null)
                .setNeutralButton(R.string.menu_diagnostics, (d, w) -> mostrarDiagnostico())
                .show();
    }

    private void mostrarDiagnostico() {
        GerenciadorGrafico.executarComPermissao(
                this,
                () ->
                        GerenciadorGrafico.diagnostico(
                                this,
                                texto -> {
                                    if (isDestroyed()) return;
                                    TextView tv = new TextView(this);
                                    tv.setText(texto);
                                    tv.setTextSize(12f);
                                    tv.setTypeface(Typeface.MONOSPACE);
                                    tv.setTextIsSelectable(true);
                                    tv.setPadding(dp(20), dp(12), dp(20), dp(12));
                                    ScrollView sv = new ScrollView(this);
                                    sv.addView(tv);
                                    new AlertDialog.Builder(this)
                                            .setTitle(R.string.menu_diagnostics)
                                            .setView(sv)
                                            .setPositiveButton(R.string.close, null)
                                            .show();
                                }));
    }

    private void solicitarAdicaoTiles() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        StatusBarManager sbm = getSystemService(StatusBarManager.class);
        if (sbm == null) return;
        Icon icon = Icon.createWithResource(this, R.drawable.ic_grafico);

        // Encadeado: o segundo pedido só abre depois que o primeiro diálogo fechar
        sbm.requestAddTileService(
                new ComponentName(this, TileServiceLeve.class),
                getString(R.string.tile_balanced),
                icon,
                getMainExecutor(),
                r1 ->
                        sbm.requestAddTileService(
                                new ComponentName(this, TileServiceUltra.class),
                                getString(R.string.tile_performance),
                                icon,
                                getMainExecutor(),
                                r2 -> {}));
    }

    // ---------------------------------------------------------------- adaptador da lista

    private class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return visiveis.size();
        }

        @Override
        public Object getItem(int position) {
            return visiveis.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        private View criarLinha() {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));

            Holder h = new Holder();

            h.icone = new ImageView(MainActivity.this);
            LinearLayout.LayoutParams lpIcone = new LinearLayout.LayoutParams(dp(40), dp(40));
            lpIcone.setMarginEnd(dp(12));
            row.addView(h.icone, lpIcone);

            LinearLayout coluna = new LinearLayout(MainActivity.this);
            coluna.setOrientation(LinearLayout.VERTICAL);

            h.nome = new TextView(MainActivity.this);
            h.nome.setTextSize(16f);
            h.nome.setSingleLine(true);
            h.nome.setEllipsize(TextUtils.TruncateAt.END);
            coluna.addView(h.nome);

            h.pacote = new TextView(MainActivity.this);
            h.pacote.setTextSize(12f);
            h.pacote.setAlpha(0.65f);
            h.pacote.setSingleLine(true);
            h.pacote.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            coluna.addView(h.pacote);

            h.estado = new TextView(MainActivity.this);
            h.estado.setTextSize(12f);
            h.estado.setTextColor(0xFF4CAF50);
            h.estado.setSingleLine(true);
            h.estado.setEllipsize(TextUtils.TruncateAt.END);
            h.estado.setVisibility(View.GONE);
            coluna.addView(h.estado);

            row.addView(coluna, peso());

            h.chave = new Switch(MainActivity.this);
            h.chave.setFocusable(false);
            h.chave.setClickable(false);
            row.addView(h.chave);

            row.setTag(h);
            return row;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : criarLinha();
            Holder h = (Holder) v.getTag();
            ItemApp item = visiveis.get(position);

            Drawable icone = cacheIcones.get(item.pacote);
            if (icone == null) {
                icone = getPackageManager().getApplicationIcon(item.info);
                cacheIcones.put(item.pacote, icone);
            }
            h.icone.setImageDrawable(icone);
            h.nome.setText(item.nome);
            h.pacote.setText(item.pacote);
            h.chave.setChecked(marcado(item));

            Integer pct = aplicados.get(item.pacote);
            if (pct == null) {
                h.estado.setVisibility(View.GONE);
            } else {
                h.estado.setVisibility(View.VISIBLE);
                h.estado.setText(
                        marcado(item)
                                ? getString(R.string.applied_badge, pct)
                                : getString(R.string.applied_still));
            }
            return v;
        }
    }
}
