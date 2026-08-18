import Toybox.Application.Storage;
import Toybox.Attention;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

//! Phone-backed metronome remote with a local haptic fallback.
//! Keep dictionaries tiny — BLE payloads are small and not beat-accurate.
class MetromSession {
    private var _bpm as Number = 120;
    private var _beats as Number = 4;
    private var _note as Number = 4;
    private var _playing as Boolean = false;
    private var _beat as Number = -1;
    private var _accent as Boolean = false;
    private var _haptics as Boolean = true;
    private var _linked as Boolean = false;
    private var _status as String = "READY";
    private var _phase as String = "IDLE";
    private var _timer as Timer.Timer?;
    private var _meterIndex as Number = 2;

    private const MIN_BPM = 30;
    private const MAX_BPM = 300;
    private const METERS = [
        [2, 4], [3, 4], [4, 4], [5, 4],
        [3, 8], [5, 8], [6, 8], [7, 8], [9, 8], [12, 8]
    ] as Array< Array<Number> >;

    public function initialize() {
        var bpm = Storage.getValue("bpm");
        if (bpm instanceof Number) {
            _bpm = clampBpm(bpm as Number);
        }
        var beats = Storage.getValue("beats");
        var note = Storage.getValue("note");
        if ((beats instanceof Number) && (note instanceof Number)) {
            _beats = beats as Number;
            _note = note as Number;
            syncMeterIndex();
        }
        var hap = Storage.getValue("haptics");
        if (hap instanceof Boolean) {
            _haptics = hap as Boolean;
        }
    }

    public function bpm() as Number { return _bpm; }
    public function beats() as Number { return _beats; }
    public function note() as Number { return _note; }
    public function playing() as Boolean { return _playing; }
    public function beat() as Number { return _beat; }
    public function accent() as Boolean { return _accent; }
    public function haptics() as Boolean { return _haptics; }
    public function linked() as Boolean { return _linked; }
    public function status() as String { return _status; }
    public function meterLabel() as String { return _beats + "/" + _note; }

    public function persist() as Void {
        Storage.setValue("bpm", _bpm);
        Storage.setValue("beats", _beats);
        Storage.setValue("note", _note);
        Storage.setValue("haptics", _haptics);
    }

    public function requestSync() as Void {
        sendCmd({ "t" => "cmd", "a" => "sync" });
    }

    // Debug (simulator) builds omit Attention.vibrate — the CIQ window
    // shakes on every vibe, which at metronome rates looks like an earthquake.
    (:debug)
    private function skipVibe() as Boolean {
        return true;
    }

    (:release)
    private function skipVibe() as Boolean {
        return false;
    }

    // CIQ Simulator 9.1/9.2 SIGSEGV on Communications.transmit over ADB.
    // Phone → watch still works; local play/BPM still run on the watch.
    (:debug)
    private function skipTransmit() as Boolean {
        return true;
    }

    (:release)
    private function skipTransmit() as Boolean {
        return false;
    }

    public function togglePlay() as Void {
        sendCmd({ "t" => "cmd", "a" => "toggle" });
        if (_playing) {
            stopLocal();
            _status = "READY";
        } else {
            startLocal();
        }
        WatchUi.requestUpdate();
    }

    public function nudge(delta as Number) as Void {
        setBpm(_bpm + delta);
        sendCmd({ "t" => "cmd", "a" => "nudge", "d" => delta });
    }

    public function tapTempo() as Void {
        sendCmd({ "t" => "cmd", "a" => "tap" });
        _status = "TAP";
        WatchUi.requestUpdate();
    }

    public function toggleHaptics() as Void {
        _haptics = !_haptics;
        persist();
        WatchUi.requestUpdate();
    }

    public function cycleMeter() as Void {
        _meterIndex = (_meterIndex + 1) % METERS.size();
        _beats = METERS[_meterIndex][0];
        _note = METERS[_meterIndex][1];
        if (_beat >= _beats) {
            _beat = 0;
        }
        persist();
        sendCmd({ "t" => "cmd", "a" => "meter", "b" => _beats, "n" => _note });
        WatchUi.requestUpdate();
    }

    //! Phone → watch state dump. Visual clock stays local (BLE is not sample-accurate).
    public function applyPhoneState(data as Dictionary) as Void {
        _linked = true;
        var bpm = data.get("bpm");
        if (bpm instanceof Number) {
            _bpm = clampBpm(bpm as Number);
        }
        var beats = data.get("beats");
        if (beats instanceof Number) {
            _beats = beats as Number;
        }
        var note = data.get("note");
        if (note instanceof Number) {
            _note = note as Number;
        }
        syncMeterIndex();
        var phase = data.get("phase");
        if (phase instanceof String) {
            _phase = phase as String;
        }
        var st = data.get("st");
        if (st instanceof String) {
            _status = st as String;
        }
        var play = data.get("play");
        var shouldPlay = false;
        if (play instanceof Number) {
            shouldPlay = (play as Number) != 0;
        } else if (play instanceof Boolean) {
            shouldPlay = play as Boolean;
        }
        if (shouldPlay && !_playing) {
            startLocal();
        } else if (!shouldPlay && _playing) {
            stopLocal();
        } else if (shouldPlay && _playing) {
            restartTimer();
        }
        if (shouldPlay && (_status.equals("READY") || _status.equals("TAP"))) {
            _status = "IN TIME";
        }
        persist();
        WatchUi.requestUpdate();
    }

    public function markUnlinked() as Void {
        _linked = false;
        WatchUi.requestUpdate();
    }

    public function onTimerBeat() as Void {
        if (!_playing) {
            return;
        }
        _beat = (_beat + 1) % _beats;
        _accent = (_beat == 0);
        click();
        WatchUi.requestUpdate();
    }

    private function setBpm(value as Number) as Void {
        _bpm = clampBpm(value);
        persist();
        if (_playing) {
            restartTimer();
        }
        WatchUi.requestUpdate();
    }

    private function startLocal() as Void {
        _playing = true;
        _beat = 0;
        _accent = true;
        _status = _linked ? "IN TIME" : "LOCAL";
        click();
        restartTimer();
    }

    private function stopLocal() as Void {
        _playing = false;
        _beat = -1;
        _accent = false;
        _phase = "IDLE";
        stopTimer();
    }

    private function restartTimer() as Void {
        stopTimer();
        var ms = (60000.0 / _bpm).toNumber();
        if (ms < 50) {
            ms = 50;
        }
        _timer = new Timer.Timer();
        _timer.start(method(:onTimerBeat), ms, true);
    }

    private function stopTimer() as Void {
        if (_timer != null) {
            _timer.stop();
            _timer = null;
        }
    }

    private function click() as Void {
        // Wrist vibe is the local click. When the phone is linked it already
        // plays audio + haptics, so skip to avoid a delayed double-click.
        if (!_haptics || _linked || skipVibe()) {
            return;
        }
        if (_phase.equals("SILENT")) {
            return;
        }
        if (!(Attention has :vibrate)) {
            return;
        }
        var duty = _accent ? 80 : 40;
        var ms = _accent ? 40 : 24;
        Attention.vibrate([new Attention.VibeProfile(duty, ms)] as Array<Attention.VibeProfile>);
    }

    private function sendCmd(payload as Dictionary) as Void {
        if (skipTransmit()) {
            return;
        }
        if (!(Communications has :transmit)) {
            return;
        }
        var settings = System.getDeviceSettings();
        if ((settings has :connectionAvailable) && !settings.connectionAvailable) {
            return;
        }
        Communications.transmit(payload, null, new MetromTxListener(self));
    }

    private function clampBpm(value as Number) as Number {
        if (value < MIN_BPM) {
            return MIN_BPM;
        }
        if (value > MAX_BPM) {
            return MAX_BPM;
        }
        return value;
    }

    private function syncMeterIndex() as Void {
        for (var i = 0; i < METERS.size(); i++) {
            if ((METERS[i][0] == _beats) && (METERS[i][1] == _note)) {
                _meterIndex = i;
                return;
            }
        }
    }
}

class MetromTxListener extends Communications.ConnectionListener {
    private var _session as MetromSession;

    public function initialize(session as MetromSession) {
        Communications.ConnectionListener.initialize();
        _session = session;
    }

    public function onComplete() as Void {
    }

    public function onError() as Void {
        _session.markUnlinked();
    }
}
