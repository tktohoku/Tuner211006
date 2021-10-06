/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.gsistohoku.glass.tuner2021;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.example.glass.ui.GlassGestureDetector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements
    GlassGestureDetector.OnGestureListener {

  private static final int REQUEST_CODE = 999;
  private static final String TAG = MainActivity.class.getSimpleName();
  private static final String DELIMITER = "\n";

  private TextView resultTextView;
  private GlassGestureDetector glassGestureDetector;
  private List<String> mVoiceResults = new ArrayList<>(4);

  // サンプリングレート
  int SAMPLING_RATE = 44100;
//  int SAMPLING_RATE = 40000;
//  int SAMPLING_RATE = 30000;
  // FFTのポイント数
  int FFT_SIZE = 4096;
//  int FFT_SIZE = 2000;

  // デシベルベースラインの設定
  double dB_baseline = Math.pow(2, 15) * FFT_SIZE * Math.sqrt(2);

  // 分解能の計算
  double resol = ((SAMPLING_RATE / (double) FFT_SIZE));

  AudioRecord audioRec = null;
  boolean bIsRecording = false;
  int bufSize;
  Thread fft;

  int rec = 0;

  double cent;
  int baseFreq = 440;
  double  baseCent = 5700;

  //平均律計算のため
  public static double NOTES_IN_OCTAVE = 12;
  public static double FREQUENCY_A = 440;
  public static double NOTE_NUMBER_A = 69;

  //チューナー用配列（周波数）
  double[] tunerFreq = {261.6, 277.2, 293.7, 311.1, 329.6, 349.2, 370.0, 392.0, 415.3, 440.0, 466.2, 493.9};

  //チューナー用配列（周波数）

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    resultTextView = findViewById(R.id.results);
    glassGestureDetector = new GlassGestureDetector(this, this);
    bufSize = AudioRecord.getMinBufferSize(SAMPLING_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
    // Android 6, API 23以上でパーミッションの確認
    if(Build.VERSION.SDK_INT >= 23) {
      String[] permissions = {
              Manifest.permission.RECORD_AUDIO,
      };
      checkPermission(permissions, REQUEST_CODE);
    }
//    createAudioRecord();
//    createAudioThread();
  }

  public static double getFreq(double noteNumber) {
    return Math.pow(2d, (noteNumber - NOTE_NUMBER_A) / NOTES_IN_OCTAVE) * FREQUENCY_A;
  }

  private void createAudioRecord() {
    // AudioRecordの作成
    audioRec = new AudioRecord(MediaRecorder.AudioSource.MIC,
            SAMPLING_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufSize * 2);
    audioRec.startRecording();
  }

  private void createAudioThread() {
    //フーリエ解析スレッド
    fft = new Thread(new Runnable() {
      @Override
      public void run() {
        Log.d("debug", "run()");
        byte buf[] = new byte[bufSize * 2];
        while (bIsRecording) {
          audioRec.read(buf, 0, buf.length);

          //エンディアン変換
          ByteBuffer bf = ByteBuffer.wrap(buf);
          bf.order(ByteOrder.LITTLE_ENDIAN);
          short[] s = new short[(int) bufSize];
          for (int i = bf.position(); i < bf.capacity() / 2; i++) {
            s[i] = bf.getShort();
          }

          //FFTクラスの作成と値の引き渡し
          FFT4g fft = new FFT4g(FFT_SIZE);
          double[] FFTdata = new double[FFT_SIZE];
          for (int i = 0; i < bufSize; i++) {
            FFTdata[i] = (double) s[i];
          }
          fft.rdft(1, FFTdata);

          // デシベルの計算
          double[] dbfs = new double[FFT_SIZE / 2];
          double max_db = -120d;
          int max_i = 0;
          for (int i = 0; i < FFT_SIZE; i += 2) {
            dbfs[i / 2] = (int) (20 * Math.log10(Math.sqrt(Math
                    .pow(FFTdata[i], 2)
                    + Math.pow(FFTdata[i + 1], 2)) / dB_baseline));
            if (max_db < dbfs[i / 2]) {
              max_db = dbfs[i / 2];
              max_i = i / 2;
            }
          }

          //音量が最大の周波数と，その音量を表示
          Log.d("fft",rec+"周波数："+ resol * max_i+" [Hz] 音量：" +  max_db+" [dB]");
          //周波数からセント
          double _freq = resol * max_i;
          cent = freqToCent(_freq);
          resultTextView.setText(_freq+"[Hz]\n"+cent+"[cent]");
          Log.d("cent","freqToCent"+cent);
        }
        // 録音停止
        audioRec.stop();
        audioRec.release();
      }
    });
  }

  private double freqToCent(double resol) {
    //cent=1200*log2(対象周波数/基準周波数)+5700
    double _resol = resol;
    double _log2 = Math.log(_resol/baseFreq) / Math.log(2.0);
    Log.d("freqLog2","_resol:"+_resol+",base:"+baseFreq+",log2:"+_log2);
    return 1200 * _log2 + baseCent;
  }

  // パーミッション許可の確認
  public void checkPermission(final String[] permissions,final int request_code){
    // 許可されていないものだけダイアログが表示される
    ActivityCompat.requestPermissions(this, permissions, request_code);
  }

  // requestPermissionsのコールバック
  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    switch(requestCode) {

      case REQUEST_CODE:
        for(int i = 0; i < permissions.length; i++ ){
          if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
            Toast toast = Toast.makeText(this,
                    "Added Permission: " + permissions[i], Toast.LENGTH_SHORT);
            toast.show();
          } else {
            Toast toast = Toast.makeText(this,
                    "Rejected Permission: " + permissions[i], Toast.LENGTH_SHORT);
            toast.show();
          }
        }
        break;
      default:
        break;
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (resultCode == RESULT_OK) {
      final List<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
      Log.d(TAG, "results: " + results.toString());
      if (results != null && results.size() > 0 && !results.get(0).isEmpty()) {
        updateUI(results.get(0));
      }
    } else {
      Log.d(TAG, "Result not OK");
    }
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent ev) {
    return glassGestureDetector.onTouchEvent(ev) || super.dispatchTouchEvent(ev);
  }

  @Override
  public boolean onGesture(GlassGestureDetector.Gesture gesture) {
    switch (gesture) {
      case TAP:
//        requestVoiceRecognition();
//        createAudioRecord();
//        createAudioThread();
        rec++;
        if (rec == 0) {
//          startTuning();
          rec++;
        }
        if (bIsRecording){
          bIsRecording = false;
          Log.d(TAG, "boolean: " + bIsRecording);
//          rec = 0;
//          fft.interrupt();
        } else {
          bIsRecording = true;
          createAudioRecord();
          createAudioThread();
          fft.start();
          Log.d(TAG, "boolean: " + bIsRecording);
        }
//        startTuning();

        return true;
      case SWIPE_DOWN:
        finish();
        return true;
      default:
        return false;
    }
  }

  @Override
  protected void onResume() {
    super.onResume();

  }

  private void startTuning() {
//    audioRec.startRecording();
    //スレッドのスタート
    fft.start();
  }

  private void requestVoiceRecognition() {
    final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    startActivityForResult(intent, REQUEST_CODE);
  }

  private void updateUI(String result) {
    if (mVoiceResults.size() >= 4) {
      mVoiceResults.remove(mVoiceResults.size() - 1);
    }
    mVoiceResults.add(0, result);
    final String recognizedText = String.join(DELIMITER, mVoiceResults);
    resultTextView.setText(recognizedText);
  }
}
