package ti4.service.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.junit.jupiter.api.Test;
import ti4.discord.interactions.buttons.Buttons;
import ti4.service.emoji.MiscEmojis;
import ti4.service.emoji.UnitEmojis;
import ti4.testUtils.BaseTi4Test;

class PanelLabelsTest extends BaseTi4Test {

    @Test
    void produceIsDroppedBecauseThePanelAlreadySaysWhatTheButtonsDo() {
        assertThat(PanelLabels.tidy(Buttons.green("place_dd_101", "Produce Destroyer", UnitEmojis.destroyer))
                        .getLabel())
                .isEqualTo("Destroyer");
        assertThat(PanelLabels.tidy(Buttons.green("place_gf_jord", "Produce 1 Infantry on Jord"))
                        .getLabel())
                .isEqualTo("1 Infantry on Jord");
        // Only the word at the start goes; other labels are left alone.
        assertThat(PanelLabels.tidy(Buttons.red("deleteButtons_x", "Done Producing Units"))
                        .getLabel())
                .isEqualTo("Done Producing Units");
    }

    @Test
    void tradeGoodButtonsBecomeShortWithTheTradeGoodEmojiAndKeepTheirIds() {
        Button one = PanelLabels.tidy(Buttons.red("reduceTG_1_res", "Spend 1 Trade Good"));
        Button three = PanelLabels.tidy(Buttons.red("reduceTG_3_inftech", "Spend 3 Trade Goods"));

        assertThat(one.getLabel()).isEqualTo("1 TG");
        assertThat(one.getEmoji()).isEqualTo(MiscEmojis.tg.asEmoji());
        assertThat(three.getLabel()).isEqualTo("3 TG");
        assertThat(three.getCustomId()).isEqualTo("reduceTG_3_inftech");
    }

    @Test
    void rowsAreTidiedButtonByButton() {
        List<ActionRow> rows = PanelLabels.tidyRows(List.of(ActionRow.of(
                Buttons.green("place_cv_101", "Produce Carrier"),
                Buttons.red("reduceTG_2_res", "Spend 2 Trade Goods"))));

        assertThat(rows.getFirst().getButtons()).extracting(Button::getLabel).containsExactly("Carrier", "2 TG");
    }
}
