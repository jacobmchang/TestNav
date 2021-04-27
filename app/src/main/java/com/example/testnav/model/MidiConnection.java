package com.example.testnav.model;

import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;


/**
 * Singleton repository representing available MIDI devices and connecting to them
 * based on https://developer.android.com/reference/android/media/midi/package-summary
 */
public class MidiConnection {
  
  
  private static MidiConnection instance;
  
  public static MidiConnection getInstance(Context ctx) {
    if (instance == null) {
      instance = new MidiConnection(ctx);
    }
    return instance;
  }
  
  
  
  // reference to midi manager
  private final MidiManager m;
  
  // reference to internal midi synth "device"
  private final MidiPlayer mInternalPlayer;
  
  
  // livedata to notify whether an external midi device is available to connect to. value never null
  // derived from m.getDevices()
  private final MutableLiveData<Boolean> mExternalAvailable = new MutableLiveData<>(false);
  
  public LiveData<Boolean> getExternalAvailable() {
    return mExternalAvailable;
  }
  
  
  // represents which device type is selected
  public enum DeviceSelection {
    // internal synth within the app
    INTERNAL,
    // external USB midi device
    EXTERNAL,
  }
  
  // livedata to hold which device type is selected. value never null.
  // this should only be EXTERNAL if externalAvailable is true
  private final MutableLiveData<DeviceSelection> mDeviceSelection =
    new MutableLiveData<>(DeviceSelection.INTERNAL);
  
  public LiveData<DeviceSelection> getDeviceSelection() {
    return mDeviceSelection;
  }
  
  
  // wrapper that handles opening and closing a device. represents currently selected external
  // device, if any
  private @Nullable Device mDevice = null;
  
  
  
  
  /**
   * sets up event listeners for device selection and plug-and-play
   * @param ctx only used to get midi service
   */
  private MidiConnection(Context ctx) {
    
    // getting application context from ctx might be a better practice than just using ctx
    m = (MidiManager)ctx.getApplicationContext().getSystemService(Context.MIDI_SERVICE);
  
    mInternalPlayer = new MidiPlayer(ctx);
    
    // initialize state
    onDeviceInfosChanged();
    
    // plug-n-play when new device connected, and cleanup when device disconnected
    m.registerDeviceCallback(new MidiManager.DeviceCallback() {
      public void onDeviceAdded( MidiDeviceInfo newInfo ) {
        onDeviceInfosChanged();
      }
      public void onDeviceRemoved( MidiDeviceInfo removedInfo ) {
        onDeviceInfosChanged();
      }
    }, new Handler(Looper.getMainLooper()));
    
  }
  
  
  private void onDeviceInfosChanged() {
    DeviceSelection currSelection = mDeviceSelection.getValue();
    MidiDeviceInfo[] newDeviceInfos = m.getDevices();
    
    mExternalAvailable.setValue(newDeviceInfos.length != 0);
    
    if (!mExternalAvailable.getValue() && currSelection == DeviceSelection.EXTERNAL) {
      // we were set to external device, but external devices are no longer available!
      // TODO inform ui?
      mDeviceSelection.setValue(DeviceSelection.INTERNAL);
    }
  
    // set device if different from current device
    setDevice(mDeviceSelection.getValue() == DeviceSelection.EXTERNAL ? newDeviceInfos[0] : null);
  }
  
  
  private void onDeviceOpenFailed() {
    mDeviceSelection.setValue(DeviceSelection.INTERNAL);
    setDevice(null);
  }
  
  
  /**
   * public method for ui to call
   */
  public void setDeviceSelection(@NotNull DeviceSelection newSelection) {
    if (mExternalAvailable.getValue()) {
      // if external available, let user select whatever they want
      mDeviceSelection.setValue(newSelection);
    } else {
      // always set to INTERNAL
      // if newSelection is EXTERNAL, that would be an error in request! ignore it
      mDeviceSelection.setValue(DeviceSelection.INTERNAL);
    }
    
    MidiDeviceInfo[] currDeviceInfos = m.getDevices();
    
    // set device if different from current device
    setDevice(mDeviceSelection.getValue() == DeviceSelection.EXTERNAL ? currDeviceInfos[0] : null);
  }
  
  
  
  private void setDevice(@Nullable MidiDeviceInfo info) {
    // lots of cases: mDevice is nullable, info is nullable, mDevice.mInfo may or may not equal info
    if (mDevice == null) {
      mDevice = info == null ? null : new Device(info);
      return;
    }
    if (!mDevice.mInfo.equals(info)) {
      mDevice.close();
      mDevice = info == null ? null : new Device(info);
    }
    // do nothing if mDevice existed and its info equaled info
  }
  
  
  
  
  
  /**
   * encapsulating wrapper around opening a device
   */
  private class Device implements Closeable {
    public final @NotNull MidiDeviceInfo mInfo;
    public @Nullable MidiDevice mDevice;
    public @Nullable MidiInputPort mPort;
  
    // public Device from(@Nullable MidiDeviceInfo info) {
    //   return info == null ? null : new Device(info);
    // }
    private Device(@NotNull MidiDeviceInfo info) {
      mInfo = info;
      
      m.openDevice(info, newDevice -> {
        if (newDevice == null) {
          Log.e("MIDI", "could not open device " + info);
          onDeviceOpenFailed();
          return;
        }
        mDevice = newDevice;
        mPort = mDevice.openInputPort(0);
      }, new Handler(Looper.getMainLooper()));
      
    }
    
    public void close() {
      if (mPort != null) tryClose(mPort);
      if (mDevice != null) tryClose(mDevice);
    }
    private void tryClose(Closeable resource) {
      try {
        resource.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
  
  
  
  
  
  
  
  /**
   * There are two ways to turn a note off: sending a NOTE_OFF message, or sending a NOTE_ON with
   * velocity 0. For some reason, the second method is preferred.
   */

  private static final byte NOTE_ON_STATUS = (byte) 0x90;
  // private static final byte NOTE_OFF_STATUS = (byte) 0x80;
  private static final byte NOTE_OFF_VELOCITY = (byte) 0;
  
  
  /**
   * based on https://developer.android.com/reference/android/media/midi/package-summary#send_a_noteon
   * 
   * @param pitch 0-127
   * @param velocity 0-127
   */
  public void sendNoteOn(int pitch, int velocity) {
    if (mDeviceSelection.getValue() == DeviceSelection.INTERNAL) {
      // play internal synth
      mInternalPlayer.noteOn(pitch, velocity);
    } else {
      // selection is EXTERNAL
      sendExternalNoteMessage(pitch, velocity);
    }
  }
  
  public void sendNoteOff(int pitch) {
    if (mDeviceSelection.getValue() == DeviceSelection.INTERNAL) {
      // play internal synth
      mInternalPlayer.noteOff(pitch);
    } else {
      // selection is EXTERNAL
      sendExternalNoteMessage(pitch, NOTE_OFF_VELOCITY);
    }
  }
  
  
  private void sendExternalNoteMessage(int pitch, int velocity) {
    if (mDevice != null && mDevice.mPort != null) {
      // selection is EXTERNAL and port is available
    
      byte[] buffer = new byte[4];
      int numBytes = 0;
    
      // hardcoded channel for now
      // MIDI channels 1-16 are encoded as 0-15.
      int channel = 1;
      buffer[numBytes++] = (byte)(NOTE_ON_STATUS + (channel - 1)); // note on
      buffer[numBytes++] = (byte)pitch;
      buffer[numBytes++] = (byte)velocity;
    
      int offset = 0;
      // post is non-blocking
      try {
        mDevice.mPort.send(buffer, offset, numBytes);
      } catch (IOException e) {
        Log.e("send midi", Arrays.toString(e.getStackTrace()));
      }
    }
  }
  
  
}
