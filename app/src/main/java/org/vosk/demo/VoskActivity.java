// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.demo;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.renderscript.ScriptGroup;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.ToggleButton;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.SpeakerModel;
import org.vosk.android.RecognitionListener;

//import org.vosk.android.SpeechStreamService;
import org.vosk.android.StorageService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;


import com.arthenica.mobileffmpeg.FFmpeg;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import static org.vosk.android.StorageService.sync;

public class VoskActivity extends Activity implements
        RecognitionListener {

    static private final int STATE_START = 0;
    static private final int STATE_READY = 1;
    static private final int STATE_DONE = 2;
    static private final int STATE_FILE_RECOG = 3;
    static private final int STATE_MIC_RECOG = 4;
    static private final int STATE_FILE_ENROLL = 5;
    static private final int STATE_MIC_ENROLL = 6;
    static private final int STATE_RECORD =7;
    static private final int STATE_SHOW =8;

    static private int SWITCH_STATE =0;

    /* Used to handle permission request */
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private Model model;
    private SpeakerModel spkMod;
    private SpeechService speechService;
    private SpeechStreamService speechStreamService;
    private TextView resultView;
    private StorageService ss;

    private String audioFilePath;
    private boolean isRecording = false;
    private MediaRecorder recorder;

    private volatile String RecogResult;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());


    ArrayList<String> names = new ArrayList<String>();

    File dataDir;

    private Map<String, Double[]> dataMap = new HashMap<>();

    //private PrintWriter writer;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);


        // Setup layout
        resultView = findViewById(R.id.result_text);
        setUiState(STATE_START);

        findViewById(R.id.recognize_file).setOnClickListener(view -> recognizeFile());
        findViewById(R.id.recognize_mic).setOnClickListener(view -> recognizeMicrophone());
        ((ToggleButton) findViewById(R.id.pause)).setOnCheckedChangeListener((view, isChecked) -> pause(isChecked));
        findViewById(R.id.enroll_file).setOnClickListener(view -> enrollFile());
        findViewById(R.id.enroll_mic).setOnClickListener(view -> enrollMicrophone());
        findViewById(R.id.record).setOnClickListener(view -> record());
        findViewById(R.id.show_file).setOnClickListener(view -> showFilesButtonClick());

        LibVosk.setLogLevel(LogLevel.INFO);

        // Check if user has given permission to record audio, init the model after permission is granted
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
            initSpk();
        }
        dataDir = new File(getFilesDir(), "dataDir");
        System.out.println(dataDir.getAbsolutePath().toString());

/*        File recordDir = new File(getFilesDir(), "record");
        if (!recordDir.exists()) {
            if (recordDir.mkdir()) {
                Log.d("MyApp", "record directory created");
            } else {
                Log.e("MyApp", "Failed to create record directory");
            }
        } else {
            Log.d("MyApp", "record directory already exists");
        }
    }*/
        File recordDir = new File(getExternalFilesDir(null), "record");
        if (!recordDir.exists()) {
            if (recordDir.mkdirs()) {
                Log.d("MyApp", "record directory created");
            } else {
                Log.e("MyApp", "Failed to create record directory");
            }
        } else {
            Log.d("MyApp", "record directory already exists");
        }



    }

    private void initModel() {
        ss.unpack(this, "model-en-us", "model",
                (model) -> {
                    this.model = model;
                    setUiState(STATE_READY);
                },
                (exception) -> setErrorState("Failed to unpack the model" + exception.getMessage()));
    }

    private void initSpk() {
        unpackS(this, "spk-model", "spkMod",
                (spkMod) -> {
                    this.spkMod = spkMod;
                    setUiState(STATE_READY);
                },
                (exception) -> setErrorState("Failed to unpack the model" + exception.getMessage()));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Recognizer initialization is a time-consuming and it involves IO,
                // so we execute it in async task
                initModel();
                initSpk();
            } else {
                finish();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }

        if (speechStreamService != null) {
            speechStreamService.stop();
        }
        /*if (writer != null) {
            writer.close();
        }*/
    }


    //this method is for getting results after using the microphone
    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onResult(String hypothesis) {

        if (hypothesis.contains("\"spk\"")) {
            Gson gson = new Gson();

            hypothesis = hypothesis.replaceAll(" ", "");
            String hypothesis2 = hypothesis.substring(hypothesis.indexOf("\"spk\""), hypothesis.lastIndexOf("]") + 1);
            String arrayPart = hypothesis2.substring(hypothesis2.indexOf("[") + 1, hypothesis2.lastIndexOf("]"));
            String[] stringDouble = arrayPart.split(",");
            Double[] inputValues = Arrays.stream(stringDouble).map(Double::valueOf).toArray(Double[]::new);
            System.out.println(Arrays.toString(stringDouble));


            File infoDir = new File(getExternalFilesDir(null), "info");
            File jsonFile = new File(infoDir, "list.json");

            try {
                FileReader reader = new FileReader(jsonFile);

                JsonArray fileList = gson.fromJson(reader, JsonArray.class);
                double maxSimilarity = -1;

                this.RecogResult = "Speaker is not enrolled";



                for (JsonElement fileElement : fileList) {
                    JsonObject fileObject = fileElement.getAsJsonObject();
                    JsonArray valuesArray = fileObject.getAsJsonArray("values");
                    Double[] fileValues = new Double[valuesArray.size()];
                    for (int i = 0; i < valuesArray.size(); i++) {
                        fileValues[i] = valuesArray.get(i).getAsDouble();
                    }
                    double similarity = cosineSimilarity(inputValues, fileValues);
                    if (similarity > maxSimilarity) {
                        maxSimilarity = similarity;

                        this.RecogResult = fileObject.get("filename").getAsString();


                    }
                }
                if (maxSimilarity > 0.15 ) {
                    System.out.println(this.RecogResult);
                    System.out.println(maxSimilarity);

                }else{
                    System.out.println(maxSimilarity);

                    this.RecogResult = "Speaker is not enrolled";

                }
                System.out.println(this.RecogResult);

            } catch (FileNotFoundException e) {
                e.printStackTrace();
                Log.e("MyApp", "Error: JSON file not found");
            }
            mainHandler.post(() -> resultView.setText(this.RecogResult));
        }else{

            Log.e("MyApp", "File cannot be recognized");
        }

    }

    /*public void onResult(String hypothesis) {
        if (hypothesis.contains("\"spk\"")) {
            hypothesis = hypothesis.replaceAll(" ", "");
            String[] hypotheses = hypothesis.split("],");

            for (String hypothesis2 : hypotheses) {
                int startIndex = hypothesis2.indexOf("\"spk\"");
                if (startIndex == -1) { // "spk" not found, start from "["
                    startIndex = hypothesis2.indexOf("[");
                }
                if (startIndex != -1) { // Check if "[" was found
                    int endIndex = hypothesis2.indexOf("]", startIndex); // Get the index of "]" that comes after the startIndex
                    if (endIndex != -1) { // Check if "]" was found
                        hypothesis2 = hypothesis2.substring(startIndex, endIndex + 1);
                        String[] stringDouble = hypothesis2.substring(hypothesis2.indexOf("[") + 1, hypothesis2.lastIndexOf("]")).split(",");
                        for (Map.Entry<String, Double[]> entry : dataMap.entrySet()) {
                            if (entry.getValue() == null) {
                                Double[] values = new Double[stringDouble.length];
                                for (int i = 0; i < stringDouble.length; i++) {
                                    values[i] = Double.valueOf(stringDouble[i]);
                                }
                                entry.setValue(values);
                                break;  // Assuming there is only one entry with null value
                            }
                        }
                    } else {
                        Log.e("MyApp", "No \"]\" found in hypothesis2 after \"[\" or \"spk\" by onResult");
                        // Handle the case where "]" is not found...
                    }

                } else {
                    Log.e("MyApp", "Neither \"spk\" nor \"[\" found in hypothesis2");
                    // Handle the case where neither "spk" nor "[" is found...
                }


            }

            // Write the dataMap to JSON file
            try {
                JSONArray jsonArray = new JSONArray();
                for (Map.Entry<String, Double[]> entry : dataMap.entrySet()) {
                    String fileName = entry.getKey();
                    Double[] values = entry.getValue();

                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("filename", fileName);
                    JSONArray valuesArray = new JSONArray();
                    if (values != null) {
                        for (Double value : values) {
                            valuesArray.put(value);
                        }
                    }
                    jsonObject.put("values", valuesArray);

                    jsonArray.put(jsonObject);
                }

                String jsonStr = jsonArray.toString(4); // 4 is the indentation level

                File infoDir = new File(getExternalFilesDir(null), "info");
                if (!infoDir.exists()) {
                    if (!infoDir.mkdirs()) {
                        Log.e("MyApp", "Failed to create info directory");
                        return;
                    }
                }
                File listFile = new File(infoDir, "list.json");
                if (listFile.exists()) {
                    if (!listFile.delete()) {
                        Log.e("MyApp", "Failed to delete existing list.json file");
                    }
                }
                BufferedWriter writer = new BufferedWriter(new FileWriter(listFile));
                writer.write(jsonStr);
                writer.close();
            } catch (IOException e) {
                Log.e("MyApp", "Failed to write to list.json file", e);
            } catch (JSONException e) {
                Log.e("MyApp", "Failed to construct JSON object", e);
            }
        }
    }*/


    //this method is for getting results after reading in a file
    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override

    /*public void onFinalResult(String hypothesis) {
        if (hypothesis.contains("\"spk\"")) {
            hypothesis = hypothesis.replaceAll(" ", "");
            String hypothesis2 = hypothesis.substring(hypothesis.indexOf("\"spk\""), hypothesis.lastIndexOf("]") + 1);
            String[] stringDouble = hypothesis2.substring(hypothesis2.indexOf("[") + 1, hypothesis2.lastIndexOf("]")).split(",");
            System.out.println(Arrays.toString(stringDouble));


            try {
                for (String str : stringDouble) {
                    writer.println(str);
                }
                writer.flush();
            } catch (Exception e) {
                Log.e("MyApp", "Failed to write to list.txt", e);
            }
            // Update the array values in the dataMap
            for (Map.Entry<String, Double[]> entry : dataMap.entrySet()) {
                if (entry.getValue() == null) {
                    Double[] values = new Double[stringDouble.length];
                    for (int i = 0; i < stringDouble.length; i++) {
                        values[i] = Double.valueOf(stringDouble[i]);
                    }
                    entry.setValue(values);
                    break;  // Assuming there is only one entry with null value
                }
            }

            // Write the dataMap to JSON file
            try {
                JSONArray jsonArray = new JSONArray();
                for (Map.Entry<String, Double[]> entry : dataMap.entrySet()) {
                    String fileName = entry.getKey();
                    Double[] values = entry.getValue();

                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("filename", fileName);
                    JSONArray valuesArray = new JSONArray();
                    for (Double value : values) {
                        valuesArray.put(value);
                    }
                    jsonObject.put("values", valuesArray);

                    jsonArray.put(jsonObject);
                }

                String jsonStr = jsonArray.toString(4); // 4 is the indentation level

                File infoDir = new File(getExternalFilesDir(null), "info");
                if (!infoDir.exists()) {
                    if (!infoDir.mkdirs()) {
                        Log.e("MyApp", "Failed to create info directory");
                        return;
                    }
                }
                File listFile = new File(infoDir, "list.json");
                if (listFile.exists()) {
                    if (!listFile.delete()) {
                        Log.e("MyApp", "Failed to delete existing list.json file");
                    }
                }
                BufferedWriter writer = new BufferedWriter(new FileWriter(listFile));
                writer.write(jsonStr);
                writer.close();
            } catch (IOException e) {
                Log.e("MyApp", "Failed to write to list.json file", e);
            } catch (JSONException e) {
                Log.e("MyApp", "Failed to construct JSON object", e);
            }
        }
    }*/
    public void onFinalResult(String hypothesis) {
        System.out.println(hypothesis);
        switch(SWITCH_STATE) {
            case 0:
                if (hypothesis.contains("\"spk\"")) {
                    hypothesis = hypothesis.replaceAll(" ", "");
                    String hypothesis2 = hypothesis.substring(hypothesis.indexOf("\"spk\""), hypothesis.lastIndexOf("]") + 1);

                    String arrayPart = hypothesis2.substring(hypothesis2.indexOf("[") + 1, hypothesis2.lastIndexOf("]"));
                    String[] stringDouble = arrayPart.split(",");
                    System.out.println(Arrays.toString(stringDouble));


                    Double[] doubleValues = new Double[stringDouble.length];
                    for (int i = 0; i < stringDouble.length; i++) {
                        doubleValues[i] = Double.valueOf(stringDouble[i]);
                    }


                    for (Map.Entry<String, Double[]> entry : dataMap.entrySet()) {
                        if (entry.getValue() == null) {
                            entry.setValue(doubleValues);
                            break;
                        }
                    }

                    try {
                        JSONArray jsonArray = new JSONArray();
                        for (Map.Entry<String, Double[]> entry : dataMap.entrySet()) {
                            String fileName = entry.getKey();
                            Double[] values = entry.getValue();

                            JSONObject jsonObject = new JSONObject();
                            jsonObject.put("filename", fileName);
                            JSONArray valuesArray = new JSONArray();
                            if (values != null) {
                                for (Double value : values) {
                                    valuesArray.put(value);
                                }
                            }
                            jsonObject.put("values", valuesArray);

                            jsonArray.put(jsonObject);
                        }

                        String jsonStr = jsonArray.toString(4);

                        File infoDir = new File(getExternalFilesDir(null), "info");
                        if (!infoDir.exists()) {
                            if (!infoDir.mkdirs()) {
                                Log.e("MyApp", "Failed to create info directory");
                                return;
                            }
                        }
                        File listFile = new File(infoDir, "list.json");

                        BufferedWriter writer = new BufferedWriter(new FileWriter(listFile));
                        writer.write(jsonStr);
                        writer.close();
                    } catch (IOException e) {
                        Log.e("MyApp", "Failed to write to list.json file", e);
                    } catch (JSONException e) {
                        Log.e("MyApp", "Failed to construct JSON object", e);
                    }
                }
                break;

            case 1:
                if (hypothesis.contains("\"spk\"")) {
                    Gson gson = new Gson();

                    hypothesis = hypothesis.replaceAll(" ", "");
                    String hypothesis2 = hypothesis.substring(hypothesis.indexOf("\"spk\""), hypothesis.lastIndexOf("]") + 1);
                    String arrayPart = hypothesis2.substring(hypothesis2.indexOf("[") + 1, hypothesis2.lastIndexOf("]"));
                    String[] stringDouble = arrayPart.split(",");
                    Double[] inputValues = Arrays.stream(stringDouble).map(Double::valueOf).toArray(Double[]::new);
                    System.out.println(Arrays.toString(stringDouble));


                    File infoDir = new File(getExternalFilesDir(null), "info");
                    File jsonFile = new File(infoDir, "list.json");

                    try {
                        FileReader reader = new FileReader(jsonFile);

                        JsonArray fileList = gson.fromJson(reader, JsonArray.class);
                        double maxSimilarity = -1;

                        this.RecogResult = "Speaker is not enrolled";



                        for (JsonElement fileElement : fileList) {
                            JsonObject fileObject = fileElement.getAsJsonObject();
                            JsonArray valuesArray = fileObject.getAsJsonArray("values");
                            Double[] fileValues = new Double[valuesArray.size()];
                            for (int i = 0; i < valuesArray.size(); i++) {
                                fileValues[i] = valuesArray.get(i).getAsDouble();
                            }
                            double similarity = cosineSimilarity(inputValues, fileValues);
                            if (similarity > maxSimilarity) {
                                maxSimilarity = similarity;

                                this.RecogResult = fileObject.get("filename").getAsString();


                            }
                        }
                        if (maxSimilarity > 0.8 ) {
                            System.out.println(this.RecogResult);
                            System.out.println(maxSimilarity);

                        }else{
                            System.out.println(maxSimilarity);

                            this.RecogResult = "Speaker is not enrolled";

                        }
                        System.out.println(this.RecogResult);

                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                        Log.e("MyApp", "Error: JSON file not found");
                    }
                    mainHandler.post(() -> resultView.setText(this.RecogResult));
                }else{

                    Log.e("MyApp", "File cannot be recognized");
                }
                SWITCH_STATE = 0;
                break;

            default:
                throw new IllegalStateException("Unexpected value: " + SWITCH_STATE);

        }
    }

    @Override
    public void onPartialResult(String hypothesis) {

        //resultView.append(hypothesis + "\n");
    }

    @Override
    public void onError(Exception e) {
        setErrorState(e.getMessage());
    }

    @Override
    public void onTimeout() {
        System.out.println("times out");
        setUiState(STATE_DONE);
    }

    private void setUiState(int state) {
        switch (state) {
            case STATE_START:
                resultView.setText(R.string.preparing);
                resultView.setMovementMethod(new ScrollingMovementMethod());
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                findViewById(R.id.enroll_file).setEnabled(false);
                findViewById(R.id.enroll_mic).setEnabled(false);
                findViewById(R.id.record).setEnabled(false);
                findViewById(R.id.show_file).setEnabled(false);
                break;
            case STATE_READY:
                resultView.setText(R.string.ready);
                ((Button) findViewById(R.id.record)).setText(R.string.record);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                ((Button) findViewById(R.id.enroll_mic)).setText(R.string.enroll_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                findViewById(R.id.enroll_file).setEnabled(true);
                findViewById(R.id.enroll_mic).setEnabled(true);
                findViewById(R.id.record).setEnabled(true);
                findViewById(R.id.show_file).setEnabled(true);
                break;
            case STATE_DONE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.recognize_file);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                ((Button) findViewById(R.id.enroll_mic)).setText(R.string.enroll_microphone);
                ((Button) findViewById(R.id.record)).setText(R.string.record);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                findViewById(R.id.enroll_file).setEnabled(true);
                findViewById(R.id.enroll_mic).setEnabled(true);
                findViewById(R.id.record).setEnabled(true);
                findViewById(R.id.show_file).setEnabled(true);
                break;
            case STATE_FILE_RECOG:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.stop_file);
                resultView.setText(getString(R.string.starting));
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                findViewById(R.id.enroll_mic).setEnabled(false);
                findViewById(R.id.enroll_file).setEnabled(false);
                findViewById(R.id.record).setEnabled(false);
                findViewById(R.id.show_file).setEnabled(false);
                break;
            case STATE_MIC_RECOG:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.stop_microphone);
                resultView.setText(getString(R.string.say_something));
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((true));
                findViewById(R.id.enroll_file).setEnabled(false);
                findViewById(R.id.enroll_mic).setEnabled(false);
                findViewById(R.id.record).setEnabled(false);
                findViewById(R.id.show_file).setEnabled(false);
                break;
            case STATE_FILE_ENROLL:
                resultView.setText(getString(R.string.starting));
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                findViewById(R.id.enroll_mic).setEnabled(true);
                findViewById(R.id.enroll_file).setEnabled(true);
                findViewById(R.id.record).setEnabled(true);
                findViewById(R.id.show_file).setEnabled(true);
                break;
            case STATE_MIC_ENROLL:
                ((Button) findViewById(R.id.enroll_mic)).setText(R.string.stop_microphone);
                resultView.setText(getString(R.string.say_something));
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((true));
                findViewById(R.id.enroll_file).setEnabled(false);
                findViewById(R.id.enroll_mic).setEnabled(true);
                findViewById(R.id.record).setEnabled(false);
                findViewById(R.id.show_file).setEnabled(false);
                break;
            case STATE_RECORD:
                ((Button) findViewById(R.id.record)).setText(R.string.stop_record);
                resultView.setText(getString(R.string.say_something));
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                findViewById(R.id.enroll_file).setEnabled(false);
                findViewById(R.id.enroll_mic).setEnabled(false);
                findViewById(R.id.record).setEnabled(true);
                findViewById(R.id.show_file).setEnabled(false);
                break;
            case STATE_SHOW:
                resultView.setText(getString(R.string.ready));
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                findViewById(R.id.enroll_file).setEnabled(false);
                findViewById(R.id.enroll_mic).setEnabled(false);
                findViewById(R.id.record).setEnabled(false);
                findViewById(R.id.show_file).setEnabled(true);
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + state);
        }
    }

    private void setErrorState(String message) {
        resultView.setText(message);
        ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
        findViewById(R.id.recognize_file).setEnabled(false);
        findViewById(R.id.recognize_mic).setEnabled(false);
        findViewById(R.id.enroll_file).setEnabled(false);
        findViewById(R.id.enroll_mic).setEnabled(false);
        findViewById(R.id.pause).setEnabled((false));
        findViewById(R.id.record).setEnabled(true);
        findViewById(R.id.show_file).setEnabled(false);
    }

    /*private void recognizeFile() {
        if (speechStreamService != null) {
            setUiState(STATE_DONE);
            speechStreamService.stop();
            speechStreamService = null;
        } else {
            setUiState(STATE_FILE_RECOG);

            try {
                AssetManager assetMan = getApplicationContext().getAssets();
                String[] s = assetMan.list("");
                for (String str : s) {
                    if (str.contains(".wav")) {
                        names.add(str);
                    }
                    Recognizer rec = new Recognizer(model, spkMod, 16000.f);
                    InputStream ais = getApplicationContext().getAssets().open(str);
                    if (ais.skip(44) != 44) throw new IOException("File too short");
                    speechStreamService = new SpeechStreamService(rec, ais, 16000);
                    speechStreamService.start(this);
                }
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }*/

    private void recognizeFile() {

        setUiState(STATE_FILE_RECOG);
        SWITCH_STATE = 1;
        try {
            File dir = new File(getExternalFilesDir(null), "record");
            String[] fileNames = dir.list();
            if (fileNames != null && fileNames.length > 0) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("choose one file");
                builder.setItems(fileNames, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String selectedFile = fileNames[which];
                        try {
                            CountDownLatch latch = new CountDownLatch(1);
                            Recognizer rec = new Recognizer(model, spkMod, 16000.f);
                            InputStream ais = new FileInputStream(new File(dir, selectedFile));
                            if (ais.skip(44) != 44) throw new IOException("File too short");
                            speechStreamService = new SpeechStreamService(rec, ais, 16000.0f, latch);
                            speechStreamService.start(VoskActivity.this);
                            try {
                                latch.await();
                            } catch (InterruptedException e) {

                            }
                            Log.e("MyApp", "finish recognition");
                            System.out.println(selectedFile);
                            speechStreamService.stop();
                            speechStreamService = null;

                        } catch (IOException e) {
                            setErrorState(e.getMessage());
                        }
                    }
                });
                builder.show();
            }
        } catch (Exception e) {
            setErrorState(e.getMessage());
        }




        setUiState(STATE_DONE);
    }



    private void recognizeMicrophone() {
        if (speechService != null) {
            setUiState(STATE_DONE);
            speechService.stop();
            speechService = null;
        } else {
            setUiState(STATE_MIC_RECOG);
            try {
                Recognizer rec = new Recognizer(model, spkMod, 16000.0f);
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

/*    private void enrollFile() {
        if (speechStreamService != null) {
            setUiState(STATE_DONE);
            speechStreamService.stop();
            speechStreamService = null;
        } else {
            setUiState(STATE_FILE_RECOG);
            try {
                AssetManager assetMan = getApplicationContext().getAssets();
                String[] s = assetMan.list("");
                for (String str : s) {
                    if (str.contains(".wav")) {
                        names.add(str);
                    }
                    Recognizer rec = new Recognizer(model, spkMod, 16000.f);
                    InputStream ais = getApplicationContext().getAssets().open(str);
                    if (ais.skip(44) != 44) throw new IOException("File too short");
                    speechStreamService = new SpeechStreamService(rec, ais, 16000);
                    speechStreamService.start(this);
                }
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }*/
    private void enrollFile() {

        setUiState(STATE_FILE_ENROLL);
        //File recordDir = new File(getApplicationContext().getFilesDir(), "record");
        //File recordDir = getApplicationContext().getDir("record", MODE_PRIVATE);
        File recordDir = new File(getExternalFilesDir(null), "record");
        if (!recordDir.exists()) {
            if (recordDir.mkdirs()) {
                Log.d("MyApp", "record directory created");
            } else {
                Log.e("MyApp", "Failed to create record directory");
                setUiState(STATE_DONE);
                return;
            }
        }

        if (!recordDir.isDirectory()) {
            Log.e("MyApp", "record directory is not a directory");
            setUiState(STATE_DONE);
            return;
        }

        String[] fileNames = recordDir.list();
        if (fileNames == null) {
            Log.e("MyApp", "Failed to list files in record directory");
            setUiState(STATE_DONE);
            return;
        }
        SWITCH_STATE = 0;
        boolean[] checkedItems = new boolean[fileNames.length];
        new AlertDialog.Builder(this)
                .setTitle("Select files to enroll")
                .setMultiChoiceItems(fileNames, checkedItems, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checkedItems[which] = isChecked;
                    }
                })
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        File enrollDir = new File(getExternalFilesDir(null), "enroll");


                        if (!enrollDir.exists()) {
                            if (!enrollDir.mkdirs()) {
                                setErrorState("Failed to create enroll directory.");
                                return;
                            }
                        }

                        for (int i = 0; i < fileNames.length; i++) {
                            if (checkedItems[i]) {
                                File srcFile = new File(recordDir, fileNames[i]);
                                File destFile = new File(enrollDir, fileNames[i]);

                                try {
                                    copyFile(srcFile, destFile);
                                } catch (IOException e) {
                                    setErrorState(e.getMessage());
                                }
                            }
                        }

                        processEnrollmentFiles(enrollDir);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        resultView.setText("enroll finish");

        setUiState(STATE_DONE);


    }

    private void processEnrollmentFiles(File enrollDir) {
        String[] s = enrollDir.list();
        try {

            File infoDir = new File(getExternalFilesDir(null), "info");
            if (!infoDir.exists()) {
                if (!infoDir.mkdirs()) {
                    Log.e("MyApp", "Failed to create info directory");
                    return;
                }
            }

            File listFile = new File(infoDir, "list.json");
            if (listFile.exists()) {
                if (!listFile.delete()) {
                    Log.e("MyApp", "Failed to delete existing list.json file");
                }
            }

            this.dataMap = new HashMap<>();

            for (String str : s) {
                if (str.contains(".wav")) {
                    names.add(str);
                    dataMap.put(str.replace(".wav", ""), null);
                }
                CountDownLatch latch = new CountDownLatch(1);
                Recognizer rec = new Recognizer(model, spkMod, 16000.f);
                InputStream ais = new FileInputStream(new File(enrollDir, str));
                if (ais.skip(44) != 44) throw new IOException("File too short");
                speechStreamService = new SpeechStreamService(rec, ais, 16000.0f, latch);
                speechStreamService.start(this);
                try {
                    latch.await();  // wait for SpeechStreamService to finish
                } catch (InterruptedException e) {
                    // handle exception
                }
                Log.e("MyApp", "finish +1");
                System.out.println(str);
                speechStreamService.stop();
                speechStreamService = null;
            }

        } catch (IOException e) {
            setErrorState(e.getMessage());
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private void enrollMicrophone() {
        if (isRecording) {
            stopRecording(audioFilePath);
            File enrollDir = new File(getExternalFilesDir(null), "enroll");
            resultView.setText(("Doing Enrollment"));
            ((Button) findViewById(R.id.enroll_mic)).setText("Please wait");
            processEnrollmentFiles(enrollDir);
            if (speechStreamService != null) {
                speechStreamService.stop();
                speechStreamService = null;
            }
            isRecording = false;
            resultView.setText("Enrollment finish");
            setUiState(STATE_DONE);

        } else {
            setUiState(STATE_MIC_ENROLL);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Enter File Name");
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            builder.setView(input);
            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String fileName = input.getText().toString();
                    audioFilePath = startRecording("enroll", fileName);
                    ((Button) findViewById(R.id.enroll_mic)).setText(R.string.stop_record);
                    isRecording = true;
                }
            });
            builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });
            builder.show();
        }



    }

/*    private void record() {
        if (isRecording) {
            stopRecording();
            isRecording = false;
            setUiState(STATE_DONE);
        } else {
            setUiState(STATE_RECORD);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Enter File Name");
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            builder.setView(input);
            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String fileName = input.getText().toString();
                    startRecording("record", fileName);
                    ((Button) findViewById(R.id.record)).setText(R.string.stop_record);
                    isRecording = true;
                }
            });
            builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });
            builder.show();
        }
    }

    private void startRecording(String folderName, String fileName) {
        File recordDir = new File(getExternalFilesDir(null), folderName);
        if (!recordDir.exists()) {
            if (!recordDir.mkdirs()) {
                Log.e("MyApp", "Failed to create record directory");
                return;
            }
        }

        String audioFilePath = new File(recordDir, fileName + ".mp3").getAbsolutePath();

        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(audioFilePath);
        recorder.setAudioSamplingRate(16000);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        try {
            recorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
        recorder.start();
    }

    private void stopRecording() {
        if (recorder != null) {
            recorder.stop();
            recorder.release();
            recorder = null;
        }
    }*/
    private void record() {
        if (isRecording) {
            stopRecording(audioFilePath);
            isRecording = false;
            setUiState(STATE_DONE);
        } else {
            setUiState(STATE_RECORD);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Enter File Name");
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            builder.setView(input);
            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String fileName = input.getText().toString();
                    audioFilePath = startRecording("record", fileName);
                    ((Button) findViewById(R.id.record)).setText(R.string.stop_record);
                    isRecording = true;
                }
            });
            builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });
            builder.show();
        }
    }

    private String startRecording(String folderName, String fileName) {
        File recordDir = new File(getExternalFilesDir(null), folderName);
        if (!recordDir.exists()) {
            if (!recordDir.mkdirs()) {
                Log.e("MyApp", "Failed to create directory");
                return null;
            }
        }

        String audioFilePath = new File(recordDir, fileName + ".mp3").getAbsolutePath();

        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(audioFilePath);
        recorder.setAudioSamplingRate(16000);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        try {
            recorder.prepare();
        } catch (IOException e) {
            Log.e("MyApp", "Failed to prepare the recorder", e);
            return null;
        }
        recorder.start();

        return audioFilePath;
    }


    private void stopRecording(String audioFilePath) {
        if (recorder != null) {
            recorder.stop();
            recorder.release();
            recorder = null;

            String wavFileName = audioFilePath.replace(".mp3", ".wav");
            try {
                int result = FFmpeg.execute("-i " + audioFilePath + " " + wavFileName);
                if (result == 0) {
                    File mp3File = new File(audioFilePath);
                    boolean deleteResult = mp3File.delete();
                    if (!deleteResult) {
                        Log.e("MyApp", "Failed to delete the original MP3 file");
                    }
                } else {
                    Log.e("MyApp", "Failed to convert the audio file");
                }
            } catch (Exception e) {
                Log.e("MyApp", "Failed to convert the audio file or delete the original file", e);
            }
        }
    }

    public static double cosineSimilarity(Double[] vectorA, Double[] vectorB) {
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        if (vectorA != null & vectorB != null && vectorA.length == vectorB.length) {
            for (int i = 0; i < vectorA.length; i++) {
                dotProduct += vectorA[i] * vectorB[i];
                normA += Math.pow(vectorA[i], 2);
                normB += Math.pow(vectorB[i], 2);
            }
            return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
        } else {
            return -1;
        }
    }

    private void pause(boolean checked) {
        if (speechService != null) {
            speechService.setPause(checked);
        }
    }

    protected static final String TAG = StorageService.class.getSimpleName();

    public interface Callback<R> {
        void onComplete(R result);
    }

    public static void unpackS(Context context, String sourcePath, final String targetPath, final StorageService.Callback<SpeakerModel> completeCallback, final StorageService.Callback<IOException> errorCallback) {
        Executor executor = Executors.newSingleThreadExecutor(); // change according to your requirements
        Handler handler = new Handler(Looper.getMainLooper());
        executor.execute(() -> {
            try {
                final String outputPath = sync(context, sourcePath, targetPath);
                SpeakerModel model = new SpeakerModel(outputPath);
                handler.post(() -> completeCallback.onComplete(model));
            } catch (final IOException e) {
                handler.post(() -> errorCallback.onComplete(e));
            }
        });
    }

    private void writeToFile(String hypo, String spkName) {
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        try {
            File spkFile = new File(dataDir, spkName + ".txt");
            FileWriter fOut = new FileWriter(spkFile);
            fOut.write(hypo);
            fOut.flush();
            fOut.close();
        } catch (Exception e) {
            setErrorState("writeToFile error");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void checkExistingSignatures(File spkDir, Double[] vector, String speakerName) {
        if (!spkDir.isDirectory()) {
            writeToFile(vector.toString(), speakerName);
        } else {
            System.out.println(spkDir.getAbsolutePath());
            File[] speakerFiles = spkDir.listFiles();
            if (speakerFiles.length == 0) {
                writeToFile(Arrays.toString(vector), speakerName);
            }
            for (File theFile : speakerFiles) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(theFile));
                    String line = reader.readLine();
                    System.out.println(line);
                    if (line.contains("[") && line.contains("]")) {
                        String[] stringDouble = line.substring(line.indexOf("[") + 1, line.lastIndexOf("]")).split(",");
                        Double[] doubleValues = Arrays.stream(stringDouble).map(Double::valueOf).toArray(Double[]::new);
                        double diff = cosineSimilarity(doubleValues, vector);
                        if (diff > 0.27) {
                            System.out.println("This speaker already exists! Updating the speaker file.");
                            resultView.append("This speaker already exists! Updating the speaker file.");
                        } else {
                            System.out.println("Writing to file.");
                            resultView.append("Writing to file.");
                            writeToFile(Arrays.toString(vector), speakerName);
                        }
                    }
                } catch (IOException e) {
                    setErrorState("checkExistingSignatures error");
                }
            }
        }
    }


    public void showFilesButtonClick() {
        File enrollDir = new File(getExternalFilesDir(null), "enroll");
        File[] files = enrollDir.listFiles();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enroll Files");


        if (files != null && files.length > 0) {
            String[] fileNames = new String[files.length];
            for (int i = 0; i < files.length; i++) {
                fileNames[i] = files[i].getName();
            }

            builder.setItems(fileNames, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // Handle file or folder selection
                    File selectedFile = files[which];
                    showOptionsDialog(selectedFile);
                }
            });
        } else {
            builder.setMessage("No files found.");
        }

        builder.setPositiveButton("OK", null);
        builder.show();
    }

    private void showOptionsDialog(final File selectedFile) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Options");

        builder.setItems(new CharSequence[]{"Rename", "Delete"}, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case 0:
                        showRenameDialog(selectedFile);
                        break;
                    case 1:
                        showDeleteConfirmationDialog(selectedFile);
                        break;
                }
            }
        });

        builder.show();
    }

    private void showRenameDialog(final File file) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename");
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String newName = input.getText().toString();
                File newFile = new File(file.getParent(), newName);
                if (file.renameTo(newFile)) {
                    resultView.setText("File is renamed");
                } else {
                    resultView.setText("File is unable to rename");
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showDeleteConfirmationDialog(final File file) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Delete");
        builder.setMessage("Are you sure you want to delete this file?");
        builder.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (file.delete()) {
                    resultView.setText("File deleted");
                } else {
                    resultView.setText("File is unable to delete");
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
}