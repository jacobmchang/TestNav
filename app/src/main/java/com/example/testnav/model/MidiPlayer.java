package com.example.testnav.model;

import android.content.Context;
import android.media.MediaPlayer;


/**
 * Represents internal midi synth "device"
 */
public class MidiPlayer {
    
    // Number of notes on the keyboard
    // valid note numbers are 0 to NUM_NOTES - 1
    public static final int NUM_NOTES = 16;
    
    // 16 Different note sounds
    static final int[] AUDIO_FILES = {
        // TODO there will be NUM_NOTES files
    };
    

    private final Context mCtx;

    // Array of mediaPlayers currently playing
    // null element means not playing
    private final MediaPlayer[] mMediaPlayers = new MediaPlayer[NUM_NOTES];
    
    
    public MidiPlayer(Context ctx) {
        
        mCtx = ctx.getApplicationContext();
    }
    

    /**
     *
     *
     * @param pitch midi pitch number 0-127
     * @param velocity 0-127, ignored
     */
    public void noteOn(int pitch, int velocity) {
        int noteNum = midiPitchToNoteNum(pitch);
        if (mMediaPlayers[noteNum] != null) {
            // already playing, restart note instead of creating player again
            mMediaPlayers[noteNum].seekTo(0);
            // do we need to call start again?
        } else {
            mMediaPlayers[noteNum] = MediaPlayer.create(mCtx, AUDIO_FILES[noteNum]);
            mMediaPlayers[noteNum].start();
        }
    }

    /**
     *
     * @param pitch midi pitch number 0-127
     */
    public void noteOff(int pitch) {
        int noteNum = midiPitchToNoteNum(pitch);
        if (mMediaPlayers[noteNum] != null) {
            mMediaPlayers[noteNum].stop();
            // release is needed to free resources, otherwise there's a limit on how many media
            // players we can instantiate
            mMediaPlayers[noteNum].release();
            mMediaPlayers[noteNum] = null;
        }
    }
    
    
    /**
     * @param pitch midi pitch number 0-127
     * @return valid note number 0 to (NUM_NOTES-1) 
     */
    private static int midiPitchToNoteNum(int pitch) {
        int noteNum = pitch - 60;
        if (noteNum < 0 || noteNum >= NUM_NOTES) {
            throw new IllegalArgumentException("Invalid midi pitch " + pitch);
        }
        return noteNum;
    }
    
}
