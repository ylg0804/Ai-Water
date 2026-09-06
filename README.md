# Hydraulic Water Monitoring Platform

The first-stage Java integration calls `yolo_waterflow_universal.onnx` with two consecutive BGR frames and the camera's 21 raw calibration parameters. It returns water level, surface velocity, discharge, and decoded detections.

## Run the first-stage regression

The project uses Spring Boot 3.2.5, Java 21, and ONNX Runtime 1.19.2. The model is in `src/main/resources/models/`; test frames are in `src/test/resources/frames/`.

```powershell
./mvnw.cmd test -Dtest=WaterflowOnnxServiceTest,HydraulicParamsTest
```

The regression runs the same universal model with F98228237 and G04690603 parameters and prints the three measurements and decoded boxes. Parameters are stored and passed through unchanged; optional alarm keys do not change the 21-value model tensor.

## Integration contract

- Input tensor: `(N,3,640,640)` BGR `float32`, with `N >= 2`; preprocessing uses centered 114-value letterbox padding.
- Raw YOLO boxes are converted from `[cx,cy,w,h]` to `[x1,y1,x2,y2]` without integer rounding or image-bound clipping.
- Water level uses the first water-surface box center; velocity and discharge use the first corresponding box `x2 / 1000` encoding.
- Set `waterflow.model-path` to load a filesystem model; the default is the classpath model `models/yolo_waterflow_universal.onnx`.
- ONNX Runtime uses default CPU multi-thread execution. The device scheduler should trigger inference every `waterflow.infer-interval-seconds=60` seconds.
- Create one `WaterflowOnnxService.newMedianSmoother()` per device and apply it before storing or pushing results. The default sliding median window is 5; startup uses the samples available so far.

The current calibration values are placeholders. Absolute values are for trend reference until field calibration is completed.
