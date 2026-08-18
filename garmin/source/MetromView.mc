import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

class MetromView extends WatchUi.View {
    private var _session as MetromSession;

    public function initialize(session as MetromSession) {
        View.initialize();
        _session = session;
    }

    public function onUpdate(dc as Dc) as Void {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var cx = w / 2;

        dc.setColor(Graphics.COLOR_TRANSPARENT, Graphics.COLOR_BLACK);
        dc.clear();

        var playing = _session.playing();
        var accent = _session.accent();
        var bpmColor = Graphics.COLOR_LT_GRAY;
        if (playing && accent) {
            bpmColor = Graphics.COLOR_ORANGE;
        } else if (playing) {
            bpmColor = Graphics.COLOR_WHITE;
        }

        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, 8, Graphics.FONT_XTINY, "METROM", Graphics.TEXT_JUSTIFY_CENTER);

        var link = _session.linked() ? "PHONE" : "LOCAL";
        dc.setColor(_session.linked() ? Graphics.COLOR_ORANGE : Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, 26, Graphics.FONT_XTINY, link, Graphics.TEXT_JUSTIFY_CENTER);

        var bpmFont = Graphics.FONT_NUMBER_HOT;
        var bpmY = (h / 2) - (dc.getFontHeight(bpmFont) / 2) - 6;
        dc.setColor(bpmColor, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, bpmY, bpmFont, _session.bpm().toString(), Graphics.TEXT_JUSTIFY_CENTER);

        drawBeats(dc, cx, bpmY + dc.getFontHeight(bpmFont) + 4);

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h - dc.getFontHeight(Graphics.FONT_TINY) - 22, Graphics.FONT_TINY, _session.meterLabel(), Graphics.TEXT_JUSTIFY_CENTER);

        var footer = playing ? _session.status() : "SELECT · PLAY";
        dc.setColor(playing ? Graphics.COLOR_ORANGE : Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.drawText(cx, h - dc.getFontHeight(Graphics.FONT_XTINY) - 6, Graphics.FONT_XTINY, footer, Graphics.TEXT_JUSTIFY_CENTER);
    }

    private function drawBeats(dc as Dc, cx as Number, y as Number) as Void {
        var n = _session.beats();
        if (n < 1) {
            return;
        }
        var r = 6;
        var gap = 16;
        var total = ((n - 1) * gap);
        var x0 = cx - (total / 2);
        var active = _session.beat();
        for (var i = 0; i < n; i++) {
            var x = x0 + (i * gap);
            if (_session.playing() && (i == active)) {
                dc.setColor(i == 0 ? Graphics.COLOR_ORANGE : Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
                dc.fillCircle(x, y, r);
            } else {
                dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
                dc.drawCircle(x, y, r);
            }
        }
    }
}
