import Toybox.Lang;
import Toybox.WatchUi;

class MetromDelegate extends WatchUi.BehaviorDelegate {
    private var _session as MetromSession;

    public function initialize(session as MetromSession) {
        BehaviorDelegate.initialize();
        _session = session;
    }

    public function onSelect() as Boolean {
        _session.togglePlay();
        return true;
    }

    public function onPreviousPage() as Boolean {
        _session.nudge(1);
        return true;
    }

    public function onNextPage() as Boolean {
        _session.nudge(-1);
        return true;
    }

    public function onHold(evt as WatchUi.ClickEvent) as Boolean {
        _session.tapTempo();
        return true;
    }

    public function onMenu() as Boolean {
        var menu = new WatchUi.Menu();
        menu.setTitle("Metrom");
        menu.addItem(_session.haptics() ? "Haptics · On" : "Haptics · Off", :haptics);
        menu.addItem("Meter · " + _session.meterLabel(), :meter);
        menu.addItem("Sync phone", :sync);
        WatchUi.pushView(menu, new MetromMenuDelegate(_session), WatchUi.SLIDE_UP);
        return true;
    }
}

class MetromMenuDelegate extends WatchUi.MenuInputDelegate {
    private var _session as MetromSession;

    public function initialize(session as MetromSession) {
        MenuInputDelegate.initialize();
        _session = session;
    }

    public function onMenuItem(item as Symbol) as Void {
        if (item == :haptics) {
            _session.toggleHaptics();
        } else if (item == :meter) {
            _session.cycleMeter();
        } else if (item == :sync) {
            _session.requestSync();
        }
    }
}
