# StepSage

*A pocket-size guide that helps visually impaired users navigate indoor spaces—no cloud, no beacons, just a phone.*

---

## What it does
StepSage looks through the rear camera, spots obstacles in real time, and speaks a short direction such as  
> “There is a chair very close to your left.”

Everything—vision, language, and speech—runs on the device, so the app works offline and never uploads a single frame.

---

## How it works
1. **CameraX** streams preview frames.  
2. **MediaPipe EfficientDet-Lite 2** finds key indoor objects.  
3. Detections are packed into a tiny JSON snapshot.  
4. **Gemma 3n INT4 (2 B)**, loaded with the LiteRT `LlmInference` API, rewrites the JSON as one guidance sentence.  
5. Tokens stream to **Android TTS**, which speaks when the sentence is complete.

The loop repeats each time the scene changes, usually in a couple of seconds.

---

## Quick start
| Step | Action |
|------|--------|
| 1 | **Install** the latest `app-release.apk` from the [Releases](../../releases) page. |
| 2 | **Download** a Gemma 3n LiteRT model (<https://www.kaggle.com/models/google/gemma-3n/>). We recommend the **2 B INT4** version for most phones. |
| 3 | Copy the `.task` file to your phone (e.g. *Downloads*). |
| 4 | Launch StepSage, pick the model file when prompted, and wait while the app copies it. This is a one time activity. |
| 5 | Point the camera ahead and listen for guidance. |

*No internet connection is required after these steps.*

---

## Build from source
```bash
git clone https://github.com/<your-username>/StepSage.git
cd StepSage/android
./gradlew installDebug   # or open with Android Studio and press Run
