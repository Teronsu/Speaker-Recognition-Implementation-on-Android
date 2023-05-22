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

import android.os.Handler;
import android.os.Looper;

import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/*
 * Service that recognizes stream audio in a  thread, passes it to a recognizer and emits
 * recognition results. Recognition events are passed to a client using
 * {@link RecognitionListener}
 */
public class SpeechStreamService {

    private final Recognizer recognizer;
    private final InputStream inputStream;
    private final int sampleRate;
    private final static float BUFFER_SIZE_SECONDS = 0.2f;
    private final int bufferSize;
    private CountDownLatch latch;

    private Thread recognizerThread;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Creates speech service.
     **/
    public SpeechStreamService(Recognizer recognizer, InputStream inputStream, float sampleRate, CountDownLatch latch) {
        this.recognizer = recognizer;
        this.sampleRate = (int) sampleRate;
        this.inputStream = inputStream;
        bufferSize = Math.round(this.sampleRate * BUFFER_SIZE_SECONDS * 2);
        this.latch = latch;
    }

    /**
     * Starts recognition. Does nothing if recognition is active.
     *
     * @return true if recognition was actually started
     */
    public boolean start(RecognitionListener listener) {
        if (null != recognizerThread)
            return false;

        recognizerThread = new RecognizerThread(listener);
        recognizerThread.start();
        return true;
    }

    /**
     * Starts recognition. After specified timeout listening stops and the
     * endOfSpeech signals about that. Does nothing if recognition is active.
     * <p>
     * timeout - timeout in milliseconds to listen.
     *
     * @return true if recognition was actually started
     */
    public boolean start(RecognitionListener listener, int timeout) {
        if (null != recognizerThread)
            return false;

        recognizerThread = new RecognizerThread(listener, timeout);
        recognizerThread.start();
        return true;
    }

    /**
     * Stops recognition. All listeners should receive final result if there is
     * any. Does nothing if recognition is not active.
     *
     * @return true if recognition was actually stopped
     */
    public boolean stop() {
        if (null == recognizerThread)
            return false;

        try {
            recognizerThread.interrupt();
            recognizerThread.join();
        } catch (InterruptedException e) {
            // Restore the interrupted status.
            Thread.currentThread().interrupt();
        }

        recognizerThread = null;
        return true;
    }

    private final class RecognizerThread extends Thread {

        private int remainingSamples;
        private final int timeoutSamples;
        private final static int NO_TIMEOUT = -1;
        RecognitionListener listener;

        public RecognizerThread(RecognitionListener listener, int timeout) {
            this.listener = listener;
            if (timeout != NO_TIMEOUT)
                this.timeoutSamples = timeout * sampleRate / 1000;
            else
                this.timeoutSamples = NO_TIMEOUT;
            this.remainingSamples = this.timeoutSamples;
        }

        public RecognizerThread(RecognitionListener listener) {
            this(listener, NO_TIMEOUT);
        }

        @Override
        public void run() {
            List<String> strings = new ArrayList<>();
            byte[] buffer = new byte[bufferSize];

            while (!interrupted()
                    && ((timeoutSamples == NO_TIMEOUT) || (remainingSamples > 0))) {
                try {
                    int nread = inputStream.read(buffer, 0, buffer.length);
                    if (nread < 0) {
                        break;
                    } else {
                        boolean isSilence = recognizer.acceptWaveForm(buffer, nread);
                        if (isSilence) {
                            final String result = recognizer.getFinalResult();
                            //mainHandler.post(() -> listener.onResult(result));

                            System.out.println("onresult");
                            System.out.println(result);
                            if (result.contains("\"spk\"")) {
                                String tempresult = result.replaceAll(" ", "");
                                String tempstring = tempresult.substring(tempresult.indexOf("\"spk\"") , tempresult.lastIndexOf("]") + 1);
                                strings.add(tempstring);
                            }

                        } else {
                            final String partialResult = recognizer.getPartialResult();
                            //mainHandler.post(() -> listener.onPartialResult(partialResult));
                            listener.onPartialResult(partialResult);
                            System.out.println("partialresult");

                        }
                    }

                    if (timeoutSamples != NO_TIMEOUT) {
                        remainingSamples = remainingSamples - nread;
                    }

                } catch (IOException e) {
                    //mainHandler.post(() -> listener.onError(e));
                    listener.onError(e);
                }
            }



            // If we met timeout signal that speech ended
            if (timeoutSamples != NO_TIMEOUT && remainingSamples <= 0) {
                //mainHandler.post(() -> listener.onTimeout());
                listener.onTimeout();

            } else {
                final String finalResult = recognizer.getFinalResult();
                //mainHandler.post(() -> listener.onFinalResult(finalResult));

                if (finalResult.contains("\"spk\"")) {
                    String tempresult = finalResult.replaceAll(" ", "");
                    String tempstring = tempresult.substring(tempresult.indexOf("\"spk\"") , tempresult.lastIndexOf("]") + 1);
                    strings.add(tempstring);
                }
            }
            List<List<Double>> arrays = new ArrayList<>();


            for (String str : strings) {
                System.out.println(str);
                String arrayStr = str.substring(str.indexOf("[") + 1, str.lastIndexOf("]"));
                String[] elements = arrayStr.split(",");
                List<Double> array = new ArrayList<>();
                for (String element : elements) {
                    array.add(Double.parseDouble(element));
                }
                arrays.add(array);
            }


            List<Double> averageArray = new ArrayList<>();
            int length = arrays.get(0).size();
            System.out.println("the length is");
            System.out.println(length);
            for (int i = 0; i < length; i++) {
                double sum = 0;
                for (List<Double> array : arrays) {
                    sum += array.get(i);
                }
                double average = (double) sum / arrays.size();
                averageArray.add(average);
            }


            StringBuilder resultBuilder = new StringBuilder("\"spk\" : [");
            for (int i = 0; i < averageArray.size(); i++) {
                double value = averageArray.get(i);
                resultBuilder.append(value);
                if (i < averageArray.size() - 1) {
                    resultBuilder.append(", ");
                }
            }
            resultBuilder.append("]");

            String superResult = resultBuilder.toString();

            listener.onFinalResult(superResult);
            System.out.println("onfinalresult");
            System.out.println(superResult);
            latch.countDown();
        }
    }
}
