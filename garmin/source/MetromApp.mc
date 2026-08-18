import Toybox.Application;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.WatchUi;

class MetromApp extends Application.AppBase {
    private var _session as MetromSession;
    private var _view as MetromView;

    public function initialize() {
        AppBase.initialize();
        _session = new MetromSession();
        _view = new MetromView(_session);
        if (Communications has :registerForPhoneAppMessages) {
            Communications.registerForPhoneAppMessages(method(:onPhone));
        }
    }

    public function onStart(state as Dictionary?) as Void {
        _session.requestSync();
    }

    public function onStop(state as Dictionary?) as Void {
        _session.persist();
    }

    public function getInitialView() as [Views] or [Views, InputDelegates] {
        return [_view, new MetromDelegate(_session)];
    }

    public function session() as MetromSession {
        return _session;
    }

    public function onPhone(msg as Communications.PhoneAppMessage) as Void {
        var data = msg.data;
        if (data instanceof Dictionary) {
            var kind = (data as Dictionary).get("t");
            if ((kind instanceof String) && (kind as String).equals("state")) {
                _session.applyPhoneState(data as Dictionary);
            }
        }
    }
}
