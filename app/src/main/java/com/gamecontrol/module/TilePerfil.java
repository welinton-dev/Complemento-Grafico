package com.gamecontrol.module;

import android.content.Context;
import android.content.SharedPreferences;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Base dos dois botões: só muda o nome do modo. A intensidade vem do que o usuário ajustou. */
public abstract class TilePerfil extends TileService {

    protected abstract String modo();

    @Override
    public void onClick() {
        Context app = getApplicationContext();
        boolean ativar = !modo().equals(GerenciadorGrafico.lerModoAtivo(app));
        String fatorAlvo = ativar ? GerenciadorGrafico.fatorDoModo(app, modo()) : null;

        // Pede a permissão do Shizuku na hora, se ainda não tiver
        GerenciadorGrafico.executarComPermissao(
                app,
                () ->
                        GerenciadorGrafico.aplicarPerfil(
                                app,
                                fatorAlvo,
                                sucesso -> {
                                    // Só muda o estado se o comando realmente funcionou
                                    if (!sucesso) return;
                                    if (ativar) {
                                        SharedPreferences prefs =
                                                app.getSharedPreferences(
                                                        GerenciadorGrafico.PREFS_NAME,
                                                        Context.MODE_PRIVATE);
                                        prefs.edit()
                                                .putString(
                                                        GerenciadorGrafico.KEY_MODO_ATIVO, modo())
                                                .apply();
                                    }
                                    atualizarEstado();
                                    GerenciadorGrafico.atualizarTiles(app);
                                }));
    }

    @Override
    public void onStartListening() {
        atualizarEstado();
    }

    private void atualizarEstado() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean ativo = modo().equals(GerenciadorGrafico.lerModoAtivo(this));
        tile.setState(ativo ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        int pct = GerenciadorGrafico.lerPercentual(this, modo());
        tile.setSubtitle(
                ativo ? getString(R.string.tile_active, GerenciadorGrafico.quantidadeAplicada(this)) : pct + "%");
        tile.updateTile();
    }
}
