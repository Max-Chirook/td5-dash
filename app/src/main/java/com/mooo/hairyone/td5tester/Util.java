package com.mooo.hairyone.td5tester;

import android.graphics.Color;
import android.graphics.PorterDuff;
import android.widget.ImageButton;

import com.mooo.hairyone.td5tester.events.MessageEvent;

import org.greenrobot.eventbus.EventBus;

public  class Util {

    public static void setImageButtonState(ImageButton imageButton, boolean enabled) {
        imageButton.setEnabled(enabled);
        if (enabled) {
            imageButton.getBackground().setColorFilter(null);
        } else {
            imageButton.getBackground().setColorFilter(Color.GRAY, PorterDuff.Mode.MULTIPLY);
        }
    }

    public static String byte_array_to_hex(byte[] data, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        return sb.toString();
    }

    public static String integer_to_binary(int value) {
        return String.format("%8s", Integer.toBinaryString(value & 0xFF)).replace(' ', '0');
    }

    public static short bytes2short(byte hi_byte, byte lo_byte) {
        // https://stackoverflow.com/questions/736815/2-bytes-to-short-java
        // https://henkelmann.eu/2011/02/a-curse-on-java-bitwise-operators/
        return (short) (hi_byte << 8 | lo_byte & 0xFF);
    }

    // The connection journal lives here rather than in a screen, so it is
    // still there after Android rebuilds the screens.
    private static final java.util.ArrayList<String> journal = new java.util.ArrayList<>();

    public static void log_msg(String msg) {
        if (msg == null || msg.isEmpty()) return;
        String line = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE)
                .format(new java.util.Date()) + "  " + msg;
        synchronized (journal) {
            journal.add(line);
            while (journal.size() > Consts.MAX_INFO_LINES) journal.remove(0);
        }
        EventBus.getDefault().post(new MessageEvent(msg));
    }

    public static java.util.List<String> journal() {
        synchronized (journal) {
            return new java.util.ArrayList<>(journal);
        }
    }


    public static int get_bit(byte value, int position)
    {
        return (value >> position) & 1;
    }

}