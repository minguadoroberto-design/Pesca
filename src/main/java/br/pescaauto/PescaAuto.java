package br.pescaauto;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Pesca automatica (servidor Rede Warriors).
 * Fique dentro do circulo de pesca e aperte P. O mod joga a isca, atrai, acerta a parte vermelha
 * e a mira no centro, e recomeca sozinho. P de novo para parar.
 *
 * Usa o proprio PescaClient do mod do servidor: chama o mesmo clicar() que o seu clique chamaria,
 * so que na hora certa (marcador dentro da zona vermelha / circulo da mira fechando).
 */
public final class PescaAuto {
    static final String PC = "com.nonopichy.yggdrasilremake.client.warriors.pesca.PescaClient";

    static final KeyMapping KEY = new KeyMapping("Pesca automatica: ligar/desligar", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "Pesca AutoFarm");

    static boolean ligado = false;
    static long ultimoIniciar, ultimoAtrair, ultimoGolpe, avisoPonto;
    static Object ultimaSessao;
    static int pescados, perdidos;

    private PescaAuto() {}

    @Mod.EventBusSubscriber(modid = "pescaauto", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void teclas(RegisterKeyMappingsEvent e) { e.register(KEY); }
    }

    @Mod.EventBusSubscriber(modid = "pescaauto", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {
        @SubscribeEvent
        public static void tick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) { ligado = false; return; }
            while (KEY.consumeClick()) alternar(mc);
        }

        // a cada quadro (60+ vezes por segundo), para nao perder a janela de clique
        @SubscribeEvent
        public static void quadro(TickEvent.RenderTickEvent e) {
            if (e.phase != TickEvent.Phase.START || !ligado) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || mc.screen != null) return;
            try { passo(mc); }
            catch (Throwable ex) { ex.printStackTrace(); ligado = false; msg(mc, "Erro, desliguei: " + ex); }
        }
    }

    static void alternar(Minecraft mc) {
        ligado = !ligado;
        ultimaSessao = null;
        ultimoIniciar = 0;
        if (ligado) { pescados = 0; perdidos = 0; }
        msg(mc, ligado ? "Pesca automatica LIGADA." : "Pesca automatica DESLIGADA. Pescados: " + pescados + ", perdidos: " + perdidos + ".");
    }

    static void passo(Minecraft mc) throws Exception {
        long agora = System.currentTimeMillis();
        Object sessao = estatico("sessao");

        // ---- sem sessao: conta o resultado da anterior e inicia outra ----
        if (sessao == null) {
            if (ultimaSessao != null) { contar(mc, ultimaSessao); ultimaSessao = null; }
            Object ponto = estatico("pontoPerto");
            if (ponto != null) {
                if (agora - ultimoIniciar > 1500) {
                    KeyMapping.click(mc.options.keyUse.getKey()); // clique direito: o servidor inicia a pesca
                    ultimoIniciar = agora;
                }
            } else if (agora - avisoPonto > 10000) {
                avisoPonto = agora;
                msg(mc, "Entre no circulo do ponto de pesca.");
            }
            return;
        }
        ultimaSessao = sessao;

        // ---- sessao em andamento: age conforme a fase ----
        Object ps = campo(sessao, "ps");
        byte fase = (Byte) campo(ps, "fase");
        switch (fase) {
            case 3 -> { // ATRAIR
                int atraidos = (Integer) campo(sessao, "atraidos");
                Object regras = campo(sessao, "regras");
                int max = ((Number) regras.getClass().getMethod("atrair").invoke(regras)).intValue();
                if (atraidos < max && agora - ultimoAtrair > 450) { clicar(); ultimoAtrair = agora; }
            }
            case 6, 7, 8 -> { // BRIGA: clica quando o marcador esta na parte vermelha
                if (agora - ultimoGolpe > 280 && naZona()) { clicar(); ultimoGolpe = agora; }
            }
            case 10 -> { // MIRA: clica quando o circulo fecha
                byte nota = (Byte) campo(sessao, "nota");
                if (nota < 0 && progressoDaMira() >= 0.985) clicar();
            }
            default -> {}
        }
    }

    static void contar(Minecraft mc, Object sessao) {
        try {
            boolean ganhou = (Boolean) campo(sessao, "ganhou");
            Object nome = campo(sessao, "nomePeixe");
            if (ganhou) { pescados++; msg(mc, "Pescou" + (nome == null ? "" : ": " + nome) + "  (total " + pescados + ")"); }
            else { perdidos++; msg(mc, "O peixe escapou  (perdidos " + perdidos + ")"); }
        } catch (Throwable ignored) {}
    }

    static void msg(Minecraft mc, String t) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[Pesca] " + t), false);
    }

    // ---------------- reflexao: PescaClient do servidor ----------------
    static Class<?> pc() throws Exception { return Class.forName(PC, true, Minecraft.class.getClassLoader()); }

    static Object estatico(String nome) throws Exception {
        Field f = pc().getDeclaredField(nome);
        f.setAccessible(true);
        return f.get(null);
    }

    static Object campo(Object o, String nome) throws Exception {
        Field f = o.getClass().getDeclaredField(nome);
        f.setAccessible(true);
        return f.get(o);
    }

    static Object chamar(String nome, Class<?>[] tipos, Object... args) throws Exception {
        Method m = pc().getDeclaredMethod(nome, tipos);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    static void clicar() throws Exception { chamar("clicar", new Class<?>[0]); }

    static boolean naZona() throws Exception {
        float marcador = (Float) chamar("marcador", new Class<?>[0]);
        return (Boolean) chamar("naZona", new Class<?>[]{float.class}, marcador);
    }

    static double progressoDaMira() throws Exception { return (Double) chamar("progressoDaMira", new Class<?>[0]); }
}
