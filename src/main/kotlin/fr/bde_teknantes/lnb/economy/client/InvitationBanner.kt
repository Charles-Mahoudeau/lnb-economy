package fr.bde_teknantes.lnb.economy.client

import com.mojang.blaze3d.platform.InputConstants
import fr.bde_teknantes.lnb.economy.LnbEconomy
import fr.bde_teknantes.lnb.economy.client.gui.CasinoUi
import fr.bde_teknantes.lnb.economy.network.InvitationAnswerPayload
import fr.bde_teknantes.lnb.economy.network.InvitationNoticePayload
import net.minecraft.client.DeltaTracker
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.gui.VanillaGuiLayers
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.handling.IPayloadContext
import org.lwjgl.glfw.GLFW
import java.util.ArrayDeque

/**
 * A new invitation to a shared account, as a banner at the top of the screen in the casino style: who invites the
 * player and where, with the two keys that answer it (J to accept, K to decline, rebindable in the controls). It goes
 * after [SHOW_MS] unanswered; the invitation still waits in "My accounts". Several invitations show one after another.
 */
object InvitationBanner {
    val ACCEPT = KeyMapping("key.lnbeconomy.invitation.accept", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.lnbeconomy")
    val DECLINE = KeyMapping("key.lnbeconomy.invitation.decline", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.lnbeconomy")

    private const val SHOW_MS = 30_000L
    private const val SLIDE_MS = 250L
    private const val WIDTH = 260
    private const val HEIGHT = 44
    private const val BUTTON_WIDTH = 92
    private const val BUTTON_HEIGHT = 16

    private class Shown(val notice: InvitationNoticePayload, var since: Long = 0L, var leavingAt: Long = -1L)

    private val queue = ArrayDeque<Shown>()

    fun handle(payload: InvitationNoticePayload, context: IPayloadContext) {
        if (queue.none { it.notice.invitationId == payload.invitationId }) queue.addLast(Shown(payload))
    }

    fun registerKeys(event: RegisterKeyMappingsEvent) {
        event.register(ACCEPT)
        event.register(DECLINE)
    }

    fun registerLayer(event: RegisterGuiLayersEvent) {
        event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, LnbEconomy.id("invitation_banner"), ::render)
    }

    fun onClientTick(event: ClientTickEvent.Post) {
        val current = queue.peekFirst()
        val now = System.currentTimeMillis()
        // Presses with no banner up are dropped, so they don't answer the next one.
        while (ACCEPT.consumeClick()) answer(current, accept = true, now)
        while (DECLINE.consumeClick()) answer(current, accept = false, now)
        if (current != null && current.since > 0 && current.leavingAt < 0 && now - current.since > SHOW_MS) current.leavingAt = now
        if (current != null && current.leavingAt >= 0 && now - current.leavingAt > SLIDE_MS) queue.pollFirst()
    }

    /** Leaving the server forgets the banners. */
    fun clear() = queue.clear()

    private fun answer(current: Shown?, accept: Boolean, now: Long) {
        if (current == null || current.leavingAt >= 0 || current.since == 0L) return
        PacketDistributor.sendToServer(InvitationAnswerPayload(current.notice.invitationId, accept))
        current.leavingAt = now
    }

    private fun render(graphics: GuiGraphics, delta: DeltaTracker) {
        val current = queue.peekFirst() ?: return
        val minecraft = Minecraft.getInstance()
        if (minecraft.options.hideGui) return
        val now = System.currentTimeMillis()
        if (current.since == 0L) current.since = now
        val shown = if (current.leavingAt >= 0) 1f - (now - current.leavingAt).toFloat() / SLIDE_MS else (now - current.since).toFloat() / SLIDE_MS
        val slide = Mth.clamp(shown, 0f, 1f)
        val font = minecraft.font
        val x = (graphics.guiWidth() - WIDTH) / 2
        val y = 4 - ((1f - slide) * (HEIGHT + 6)).toInt()

        graphics.pose().pushPose()
        graphics.pose().translate(0f, 0f, 200f)
        CasinoUi.panel(graphics, x, y, WIDTH, HEIGHT)
        // The shared account icon, twice its size.
        graphics.pose().pushPose()
        graphics.pose().translate((x + 10).toFloat(), (y + 13).toFloat(), 0f)
        graphics.pose().scale(2f, 2f, 1f)
        graphics.blitSprite(CasinoUi.ACCOUNT_SHARED, 0, 0, 9, 9)
        graphics.pose().popPose()

        val notice = current.notice
        val title = Component.translatable("gui.lnbeconomy.invitation.banner", notice.invitedBy, notice.accountName, notice.handle).string
        graphics.drawString(font, CasinoUi.fit(font, title, WIDTH - 46), x + 36, y + 9, CasinoUi.GOLD_TEXT, true)

        button(graphics, x + 36, y + 21, CasinoUi.PRIMARY.normal, Component.translatable("gui.lnbeconomy.invitation.accept", ACCEPT.translatedKeyMessage))
        button(graphics, x + 36 + BUTTON_WIDTH + 6, y + 21, CasinoUi.SECONDARY.normal, Component.translatable("gui.lnbeconomy.invitation.decline", DECLINE.translatedKeyMessage))
        graphics.pose().popPose()
    }

    private fun button(graphics: GuiGraphics, x: Int, y: Int, sprite: net.minecraft.resources.ResourceLocation, label: Component) {
        val font = Minecraft.getInstance().font
        graphics.blitSprite(sprite, x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
        val text = CasinoUi.fit(font, label.string, BUTTON_WIDTH - 6)
        graphics.drawString(font, text, x + (BUTTON_WIDTH - font.width(text)) / 2, y + 4, CasinoUi.IVORY, true)
    }
}
