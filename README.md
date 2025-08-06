# StepSage - Description
StepSage is an on-device “talking cane” for smartphone users who are blind or have low vision. By pairing the phone’s rear camera with Google’s Gemma 3n model, the app gives real-time, room-level guidance. This solves a daily pain point: indoor navigation is still fraught with hidden chairs, half-open doors, and unmarked steps that conventional GPS or beacons can’t detect. StepSage removes the dependency on cloud connectivity, protects user privacy, and works even in poor-signal environments like basements or hospital corridors.

Under the hood, each camera frame is first processed locally by MediaPipe’s object detection model, which spots key indoor objects in real time. A compact JSON snapshot of that scene is then fed into the on-device Gemma 3n LLM, which turns the raw detections into a single, conversational sentence of guidance. Finally, Android’s text-to-speech engine voices the result. Because detection, language generation, and speech all run offline on the handset, StepSage preserves user privacy, works without data service, and delivers instant, context-aware navigation cues.

-> you can test the working of this application by installing the release apk under source folder in you android mobile.

keep in mind you will be prompted to choose a model from th

