package com.gamecontrol.module;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

public class GerenciadorGrafico {

    public static final String PREFS_NAME = "config_grafica";
    public static final String KEY_MODO_ATIVO = "modo_ativo";
    public static final int REQUEST_CODE_PERMISSAO = 100;

    // Perfis (usados pelos tiles e pela explicação na tela principal)
    public static final String MODO_LEVE = "LEVE";
    public static final String MODO_ULTRA = "ULTRA";
    public static final int PADRAO_LEVE = 80; // % da resolução original
    public static final int PADRAO_ULTRA = 40;
    public static final int PERCENTUAL_MIN = 30; // abaixo disso o Android ignora o fator
    public static final int PERCENTUAL_MAX = 100;

    private static final String KEY_IGNORADOS = "jogos_ignorados"; // jogos que o usuário desmarcou
    private static final String KEY_EXTRAS = "apps_extras"; // não-jogos que o usuário marcou
    private static final String KEY_MOSTRAR_TODOS = "mostrar_todos";
    private static final String KEY_PERCENT_LEVE = "percent_leve";
    private static final String KEY_PERCENT_ULTRA = "percent_ultra";
    private static final String KEY_QTD_APLICADA = "qtd_aplicada";
    private static final String KEY_BOOT_COUNT = "boot_count_aplicado";
    private static final String KEY_ATE_REBOOT = "manter_ate_reboot";
    private static final String KEY_FORCAR_PERF = "forcar_modo_performance";
    private static final String KEY_FORCOU = "forcou_modo_aplicado";
    private static final String KEY_ULTIMO_SYNC = "ultimo_resultado_sync";
    private static final String KEY_PACOTES_ALTERADOS = "pacotes_alterados";

    private static final Pattern PACOTE_VALIDO = Pattern.compile("[A-Za-z0-9_.]+");
    // Formato exato que este app grava; usado para reconhecer sobras de versões antigas
    private static final Pattern ASSINATURA =
            Pattern.compile("mode=2,downscaleFactor=[0-9.]+,fps=0");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Jogos conhecidos: entram na lista mesmo que o app não se declare como "jogo"
    private static final Set<String> JOGOS_CONHECIDOS =
            new HashSet<>(
                    Arrays.asList(
                            "com.miHoYo.GenshinImpact",
                            "com.tencent.tmgp.pubgmhd",
                            "com.activision.callofduty.shooter",
                            "com.netease.newspike"));

    public interface Resultado {
        void aoTerminar(boolean sucesso);
    }

    public interface Texto {
        void aoReceber(String texto);
    }

    public interface Aplicados {
        void aoReceber(Map<String, Integer> pacoteParaPercentual);
    }

    // ---------------------------------------------------------------- utilitários

    private static void aviso(Context ctx, String msg) {
        MAIN.post(() -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show());
    }

    private static void aviso(Context ctx, int recurso, Object... args) {
        aviso(ctx, ctx.getString(recurso, args));
    }

    private static String ler(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String linha;
        while ((linha = r.readLine()) != null) sb.append(linha).append(" ");
        return sb.toString().trim();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- detecção de apps

    @SuppressWarnings("deprecation")
    public static boolean ehJogo(ApplicationInfo app) {
        return app.category == ApplicationInfo.CATEGORY_GAME
                || (app.flags & ApplicationInfo.FLAG_IS_GAME) != 0
                || JOGOS_CONHECIDOS.contains(app.packageName);
    }

    /**
     * Sempre devolve os jogos detectados e os apps que o usuário adicionou manualmente. Com
     * todos = true, devolve também qualquer app que tenha ícone no launcher. Sem ordenação.
     */
    @SuppressWarnings("deprecation")
    public static List<ApplicationInfo> listarApps(Context ctx, boolean todos) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> extras = appsExtras(ctx);

        Set<String> comLauncher = null;
        if (todos) {
            comLauncher = new HashSet<>();
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo r : pm.queryIntentActivities(intent, 0)) {
                comLauncher.add(r.activityInfo.packageName);
            }
        }

        List<ApplicationInfo> lista = new ArrayList<>();
        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if (app.packageName.equals(ctx.getPackageName())) continue;
            if (!PACOTE_VALIDO.matcher(app.packageName).matches()) continue;

            boolean incluir =
                    ehJogo(app)
                            || extras.contains(app.packageName)
                            || (comLauncher != null && comLauncher.contains(app.packageName));
            if (incluir) lista.add(app);
        }
        return lista;
    }

    // ---------------------------------------------------------------- seleção do usuário

    /** Jogos que o usuário desmarcou (por padrão todo jogo detectado é alterado). */
    public static Set<String> jogosIgnorados(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_IGNORADOS, new HashSet<String>()));
    }

    /** Apps que não são jogos, mas que o usuário marcou para serem alterados. */
    public static Set<String> appsExtras(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_EXTRAS, new HashSet<String>()));
    }

    public static void definirMarcado(Context ctx, ApplicationInfo app, boolean marcado) {
        List<ApplicationInfo> um = new ArrayList<>();
        um.add(app);
        definirMarcadoEmLote(ctx, um, marcado);
    }

    public static void definirMarcadoEmLote(
            Context ctx, List<ApplicationInfo> apps, boolean marcado) {
        Set<String> ignorados = jogosIgnorados(ctx);
        Set<String> extras = appsExtras(ctx);
        for (ApplicationInfo app : apps) {
            String pkg = app.packageName;
            if (ehJogo(app)) {
                if (marcado) ignorados.remove(pkg);
                else ignorados.add(pkg);
            } else {
                if (marcado) extras.add(pkg);
                else extras.remove(pkg);
            }
        }
        prefs(ctx)
                .edit()
                .putStringSet(KEY_IGNORADOS, ignorados)
                .putStringSet(KEY_EXTRAS, extras)
                .apply();
    }

    /** Desligado por padrão: aplica só os valores, sem mexer na sincronização do sistema. */
    public static boolean lerAteReboot(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ATE_REBOOT, false);
    }

    public static void salvarAteReboot(Context ctx, boolean valor) {
        prefs(ctx).edit().putBoolean(KEY_ATE_REBOOT, valor).apply();
    }

    /**
     * Além do device_config, põe o jogo no modo Performance do sistema. É o que faz o downscale
     * valer, então vem ligado por padrão.
     */
    public static boolean lerForcarPerf(Context ctx) {
        return prefs(ctx).getBoolean(KEY_FORCAR_PERF, true);
    }

    public static void salvarForcarPerf(Context ctx, boolean valor) {
        prefs(ctx).edit().putBoolean(KEY_FORCAR_PERF, valor).apply();
    }

    public static boolean lerMostrarTodos(Context ctx) {
        return prefs(ctx).getBoolean(KEY_MOSTRAR_TODOS, false);
    }

    public static void salvarMostrarTodos(Context ctx, boolean valor) {
        prefs(ctx).edit().putBoolean(KEY_MOSTRAR_TODOS, valor).apply();
    }

    // ---------------------------------------------------------------- intensidade dos perfis

    public static int lerPercentual(Context ctx, String modo) {
        boolean leve = MODO_LEVE.equals(modo);
        return prefs(ctx)
                .getInt(leve ? KEY_PERCENT_LEVE : KEY_PERCENT_ULTRA, leve ? PADRAO_LEVE : PADRAO_ULTRA);
    }

    public static void salvarPercentuais(Context ctx, int leve, int ultra) {
        prefs(ctx).edit().putInt(KEY_PERCENT_LEVE, leve).putInt(KEY_PERCENT_ULTRA, ultra).apply();
    }

    /** Ex.: 80 -> "0.8" (String.valueOf de double sempre usa ponto). */
    public static String fatorDoModo(Context ctx, String modo) {
        return String.valueOf(lerPercentual(ctx, modo) / 100.0);
    }

    // ---------------------------------------------------------------- estado do perfil

    private static int bootCount(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    /** Perfil ativo agora. Se o aparelho reiniciou desde que foi aplicado, o estado é zerado. */
    public static String lerModoAtivo(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String modo = p.getString(KEY_MODO_ATIVO, "");
        if (modo.isEmpty()) return "";
        int atual = bootCount(ctx);
        int salvo = p.getInt(KEY_BOOT_COUNT, -1);
        if (atual != -1 && salvo != -1 && atual != salvo) {
            limparEstado(ctx);
            return "";
        }
        return modo;
    }

    public static int quantidadeAplicada(Context ctx) {
        return prefs(ctx).getInt(KEY_QTD_APLICADA, 0);
    }

    public static void limparEstado(Context ctx) {
        prefs(ctx)
                .edit()
                .remove(KEY_MODO_ATIVO)
                .remove(KEY_QTD_APLICADA)
                .remove(KEY_BOOT_COUNT)
                .remove(KEY_FORCOU)
                .apply();
    }

    /** Pede aos dois tiles que se redesenhem com o estado atual. */
    public static void atualizarTiles(Context ctx) {
        Context app = ctx.getApplicationContext();
        TileService.requestListeningState(app, new ComponentName(app, TileServiceLeve.class));
        TileService.requestListeningState(app, new ComponentName(app, TileServiceUltra.class));
    }

    // ---------------------------------------------------------------- Shizuku

    private static String lerTudo(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String linha;
        while ((linha = r.readLine()) != null) sb.append(linha).append("\n");
        return sb.toString();
    }

    /** Roda um comando via Shizuku e devolve tudo que ele imprimiu (para o diagnóstico). */
    private static String lerSaidaShell(Context ctx, String comando) {
        try {
            Method m =
                    Shizuku.class.getDeclaredMethod(
                            "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[] {"sh", "-c", comando}, null, null);
            String out = lerTudo(p.getInputStream());
            String err = lerTudo(p.getErrorStream());
            p.waitFor();
            return err.isEmpty() ? out : out + "[error] " + err;
        } catch (Exception e) {
            return ctx.getString(R.string.err_failure, e.toString()) + "\n";
        }
    }

    /** Mostra o que o app pretende alterar e o que o sistema realmente tem gravado. */
    public static void diagnostico(Context ctx, Texto cb) {
        Context app = ctx.getApplicationContext();
        new Thread(
                        () -> {
                            StringBuilder sb = new StringBuilder();
                            Set<String> ignorados = jogosIgnorados(app);

                            sb.append(app.getString(R.string.diag_items)).append("\n");
                            int n = 0;
                            List<String> primeiros = new ArrayList<>();
                            for (ApplicationInfo info : listarApps(app, false)) {
                                if (!ehJogo(info) || !ignorados.contains(info.packageName)) {
                                    sb.append("• ").append(info.packageName).append("\n");
                                    if (primeiros.size() < 5) primeiros.add(info.packageName);
                                    n++;
                                }
                            }
                            if (n == 0) sb.append(app.getString(R.string.diag_none)).append("\n");

                            sb.append("\n").append(app.getString(R.string.diag_system)).append("\n");
                            sb.append(
                                    lerSaidaShell(
                                            app,
                                            "echo \"Android $(getprop ro.build.version.release)"
                                                    + " (API $(getprop ro.build.version.sdk))\"; "
                                                    + "echo \"Sync: $(device_config"
                                                    + " get_sync_disabled_for_tests)\"; "
                                                    + "device_config list game_overlay"));
                            sb.append("\n").append(app.getString(R.string.diag_last_sync)).append(": ")
                                    .append(prefs(app).getString(KEY_ULTIMO_SYNC, app.getString(R.string.diag_not_run)))
                                    .append("\n");

                            StringBuilder modos = new StringBuilder("d=$(dumpsys game 2>&1); ");
                            for (String pkg : primeiros) {
                                modos.append("echo \"== ").append(pkg).append("\"; ");
                                modos.append("r=$(echo \"$d\" | grep -i -A3 -F \"")
                                        .append(pkg)
                                        .append("\" | head -n 6); ");
                                modos.append(
                                        "if [ -n \"$r\" ]; then echo \"$r\"; else echo \""
                                                + app.getString(R.string.diag_no_data)
                                                + "\"; fi; ");
                            }
                            sb.append("\n").append(app.getString(R.string.diag_modes)).append("\n");
                            sb.append(lerSaidaShell(app, modos.toString()));

                            String texto = sb.toString();
                            MAIN.post(() -> cb.aoReceber(texto));
                        })
                .start();
    }

    private static boolean executarComandoShell(Context ctx, String comando) {
        try {
            Method m =
                    Shizuku.class.getDeclaredMethod(
                            "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[] {"sh", "-c", comando}, null, null);

            String out = ler(p.getInputStream());
            String err = ler(p.getErrorStream());
            int code = p.waitFor();
            if (code != 0 || !err.isEmpty()) {
                aviso(ctx, R.string.err_command, code, err);
                return false;
            }
            return true;
        } catch (Exception e) {
            aviso(ctx, R.string.err_failure, e.toString());
            return false;
        }
    }

    /**
     * Garante a permissão do Shizuku e só então executa a ação. Se ainda não foi autorizado, abre
     * o pedido do próprio Shizuku na hora (sem precisar ir nas configurações).
     */
    public static void executarComPermissao(Context ctx, Runnable acao) {
        Context app = ctx.getApplicationContext();
        try {
            if (!Shizuku.pingBinder()) {
                aviso(app, R.string.err_shizuku_not_running);
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                acao.run();
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                // O usuário marcou "negar e não perguntar de novo"
                aviso(app, R.string.err_permission_denied_forever);
                return;
            }
            Shizuku.addRequestPermissionResultListener(
                    new Shizuku.OnRequestPermissionResultListener() {
                        @Override
                        public void onRequestPermissionResult(int requestCode, int grantResult) {
                            if (requestCode != REQUEST_CODE_PERMISSAO) return;
                            Shizuku.removeRequestPermissionResultListener(this);
                            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                acao.run();
                            } else {
                                aviso(app, R.string.err_permission_denied);
                            }
                        }
                    });
            Shizuku.requestPermission(REQUEST_CODE_PERMISSAO);
        } catch (Throwable t) {
            aviso(app, R.string.err_shizuku_failure, t.toString());
        }
    }

    // ---------------------------------------------------------------- aplicar perfil

    /** Pacotes que este app já gravou no sistema (inclusive de jogos desinstalados depois). */
    private static Set<String> pacotesAlterados(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_PACOTES_ALTERADOS, new HashSet<String>()));
    }

    private static void anotarPacotes(Context ctx, List<String> novos) {
        Set<String> set = pacotesAlterados(ctx);
        set.addAll(novos);
        prefs(ctx).edit().putStringSet(KEY_PACOTES_ALTERADOS, set).apply();
    }

    /** Entradas no sistema com o formato exato que este app grava (sobras de versões antigas). */
    private static List<String> sobrasNoSistema(Context ctx) {
        List<String> lista = new ArrayList<>();
        String saida = lerSaidaShell(ctx, "device_config list game_overlay");
        for (String linha : saida.split("\n")) {
            int i = linha.indexOf('=');
            if (i <= 0) continue;
            String pkg = linha.substring(0, i).trim();
            String valor = linha.substring(i + 1).trim();
            if (PACOTE_VALIDO.matcher(pkg).matches() && ASSINATURA.matcher(valor).matches()) {
                lista.add(pkg);
            }
        }
        return lista;
    }

    private static final String SYNC_ATE_REBOOT =
            "device_config set_sync_disabled_for_tests until_reboot; echo \"codigo=$?\"";
    private static final String SYNC_LIGAR =
            "device_config set_sync_disabled_for_tests none 2>/dev/null";

    /**
     * fator != null aplica o downscale nos itens marcados; fator == null remove de todos e
     * religa a sincronização do sistema.
     */
    public static void aplicarPerfil(Context ctx, String fator, Resultado resultado) {
        aplicar(ctx, fator, false, resultado);
    }

    /** Remove o perfil de tudo que o app já alterou, inclusive sobras e jogos desinstalados. */
    public static void restaurarTudo(Context ctx, Resultado resultado) {
        aplicar(ctx, null, true, resultado);
    }

    private static void aplicar(
            Context ctx, String fator, boolean completa, Resultado resultado) {
        Context app = ctx.getApplicationContext();
        new Thread(
                        () -> {
                            Set<String> ignorados = jogosIgnorados(app);
                            List<String> alvo = new ArrayList<>();
                            for (ApplicationInfo info : listarApps(app, false)) {
                                // Ao remover, limpa tudo (inclusive o que foi desmarcado depois)
                                boolean marcado =
                                        !ehJogo(info) || !ignorados.contains(info.packageName);
                                if (fator == null || marcado) alvo.add(info.packageName);
                            }

                            // Só estes voltam ao modo Padrão: o que o app realmente alterou
                            Set<String> paraReverter = new HashSet<>();
                            if (fator == null) {
                                // Remoção: além dos jogos instalados, limpa o que foi anotado
                                // (mesmo desinstalado) e os jogos da lista antiga
                                Set<String> todosAlvos = new HashSet<>(alvo);
                                for (String pkg : pacotesAlterados(app)) {
                                    if (PACOTE_VALIDO.matcher(pkg).matches()) {
                                        todosAlvos.add(pkg);
                                        paraReverter.add(pkg);
                                    }
                                }
                                todosAlvos.addAll(JOGOS_CONHECIDOS);
                                if (completa) {
                                    List<String> sobras = sobrasNoSistema(app);
                                    todosAlvos.addAll(sobras);
                                    paraReverter.addAll(sobras);
                                }
                                alvo.clear();
                                alvo.addAll(todosAlvos);
                            }

                            if (alvo.isEmpty() && fator != null) {
                                aviso(app, R.string.toast_no_games);
                                notificar(resultado, false);
                                return;
                            }

                            boolean forcar = lerForcarPerf(app);
                            boolean reverter =
                                    fator == null
                                            && (forcar
                                                    || prefs(app).getBoolean(KEY_FORCOU, false));

                            if (fator != null && lerAteReboot(app)) {
                                // Guarda a resposta do sistema para aparecer no diagnóstico
                                String saida = lerSaidaShell(app, SYNC_ATE_REBOOT).trim();
                                prefs(app).edit().putString(KEY_ULTIMO_SYNC, saida).apply();
                            }

                            StringBuilder cmd = new StringBuilder();
                            for (String pacote : alvo) {
                                if (cmd.length() > 0) cmd.append(" ; ");
                                if (fator != null) {
                                    cmd.append("device_config put game_overlay ")
                                            .append(pacote)
                                            .append(" mode=2,downscaleFactor=")
                                            .append(fator)
                                            .append(",fps=0");
                                    if (forcar) {
                                        cmd.append(" ; cmd game mode performance ")
                                                .append(pacote)
                                                .append(" >/dev/null 2>&1");
                                    }
                                } else {
                                    cmd.append("device_config delete game_overlay ")
                                            .append(pacote)
                                            .append(" 2>/dev/null");
                                    if (reverter && paraReverter.contains(pacote)) {
                                        cmd.append(" ; cmd game mode standard ")
                                                .append(pacote)
                                                .append(" >/dev/null 2>&1");
                                    }
                                }
                            }
                            if (fator == null) {
                                if (cmd.length() > 0) cmd.append(" ; ");
                                cmd.append(SYNC_LIGAR);
                            }

                            boolean ok = executarComandoShell(app, cmd.toString());
                            if (ok) {
                                if (fator != null) {
                                    prefs(app)
                                            .edit()
                                            .putInt(KEY_QTD_APLICADA, alvo.size())
                                            .putInt(KEY_BOOT_COUNT, bootCount(app))
                                            .putBoolean(KEY_FORCOU, forcar)
                                            .apply();
                                    anotarPacotes(app, alvo);
                                    aviso(app, R.string.toast_applied, alvo.size());
                                } else {
                                    limparEstado(app);
                                    prefs(app).edit().remove(KEY_PACOTES_ALTERADOS).apply();
                                    aviso(
                                            app,
                                            completa
                                                    ? R.string.toast_restored
                                                    : R.string.toast_removed);
                                }
                            }
                            notificar(resultado, ok);
                        })
                .start();
    }

    /**
     * Lê o que o sistema tem gravado em game_overlay: pacote -> percentual de resolução. Devolve
     * mapa vazio se o Shizuku não estiver pronto (não pede permissão).
     */
    public static void lerAplicados(Context ctx, Aplicados cb) {
        Context app = ctx.getApplicationContext();
        new Thread(
                        () -> {
                            Map<String, Integer> mapa = new HashMap<>();
                            try {
                                if (Shizuku.pingBinder()
                                        && Shizuku.checkSelfPermission()
                                                == PackageManager.PERMISSION_GRANTED) {
                                    String saida =
                                            lerSaidaShell(app, "device_config list game_overlay");
                                    Pattern fator = Pattern.compile("downscaleFactor=([0-9.]+)");
                                    for (String linha : saida.split("\n")) {
                                        int i = linha.indexOf('=');
                                        if (i <= 0) continue;
                                        String pkg = linha.substring(0, i).trim();
                                        Matcher mt = fator.matcher(linha.substring(i + 1));
                                        if (!mt.find()) continue;
                                        try {
                                            mapa.put(
                                                    pkg,
                                                    (int)
                                                            Math.round(
                                                                    Double.parseDouble(mt.group(1))
                                                                            * 100));
                                        } catch (NumberFormatException ignorado) {
                                            // linha fora do formato esperado: pula
                                        }
                                    }
                                }
                            } catch (Throwable ignorado) {
                                // sem Shizuku: lista sem selos
                            }
                            MAIN.post(() -> cb.aoReceber(mapa));
                        })
                .start();
    }

    /** Liga ou desliga só a proteção "até reiniciar", sem mexer nos valores já gravados. */
    public static void aplicarSync(Context ctx, boolean ateReboot) {
        Context app = ctx.getApplicationContext();
        new Thread(
                        () -> {
                            String saida =
                                    ateReboot
                                            ? lerSaidaShell(app, SYNC_ATE_REBOOT).trim()
                                            : lerSaidaShell(app, SYNC_LIGAR).trim()
                                                    + app.getString(R.string.sync_off_by_user);
                            prefs(app).edit().putString(KEY_ULTIMO_SYNC, saida).apply();
                            aviso(app, ateReboot ? R.string.toast_sync_on : R.string.toast_sync_off);
                        })
                .start();
    }

    private static void notificar(Resultado r, boolean ok) {
        if (r != null) MAIN.post(() -> r.aoTerminar(ok));
    }
}
