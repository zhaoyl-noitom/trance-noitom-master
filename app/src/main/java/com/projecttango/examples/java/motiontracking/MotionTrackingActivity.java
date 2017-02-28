/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.projecttango.examples.java.motiontracking;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoInvalidException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPointCloudData;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;

import android.app.Activity;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import org.rajawali3d.scene.ASceneFrameCallback;
import org.rajawali3d.surface.RajawaliSurfaceView;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.projecttango.tangosupport.TangoSupport;
import com.trance.noitom.android.R;

/**
 * Main Activity class for the Motion Tracking API Sample. Handles the connection to the Tango
 * service and propagation of Tango pose data to OpenGL and Layout views. OpenGL rendering logic is
 * delegated to the {@link MotionTrackingRajawaliRenderer} class.
 */
public class MotionTrackingActivity extends Activity {

    private static final String TAG = MotionTrackingActivity.class.getSimpleName();

    private RajawaliSurfaceView mSurfaceView;
    private MotionTrackingRajawaliRenderer mRenderer;
    private Tango mTango;//Tango对象
    private TangoConfig mConfig;//配置

    private AtomicBoolean mIsTangoPoseReady = new AtomicBoolean(false);

    private int mCurrentDisplayOrientation = 0;//当前显示方向

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_motion_tracking);
        // OpenGL view where all of the graphics are drawn.
        mSurfaceView = (RajawaliSurfaceView) findViewById(R.id.gl_surface_view);
        mRenderer = new MotionTrackingRajawaliRenderer(this);
        
        // Get current display orientation, note that each time display orientation
        // changes, the onCreate function will be called again.
        WindowManager mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display mDisplay = mWindowManager.getDefaultDisplay();
        mCurrentDisplayOrientation = mDisplay.getOrientation();//获取当前显示方向

        // Configure OpenGL renderer.
        setupRenderer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSurfaceView.onResume();

        // Initialize Tango Service as a normal Android Service, since we call mTango.disconnect()
        // in onPause, this will unbind Tango Service, so every time when onResume gets called, we
        // should create a new Tango object.
        mTango = new Tango(MotionTrackingActivity.this, new Runnable() {
            // Pass in a Runnable to be called from UI thread when Tango is ready, this Runnable
            // will be running on a new thread.
            // When Tango is ready, we can call Tango functions safely here only when there is no UI
            // thread changes involved.
            @Override
            public void run() {
                // Synchronize against disconnecting while the service is being used in the OpenGL
                // thread or in the UI thread.
                synchronized (MotionTrackingActivity.this) {
                    try {
                        TangoSupport.initialize();//Initialize the JNI interface.
                        mConfig = setupTangoConfig(mTango);
                        mTango.connect(mConfig);
                        startupTango();//处理数据
                    } catch (TangoOutOfDateException e) {
                        Log.e(TAG, getString(R.string.exception_out_of_date), e);
                    } catch (TangoErrorException e) {
                        Log.e(TAG, getString(R.string.exception_tango_error), e);
                    } catch (TangoInvalidException e) {
                        Log.e(TAG, getString(R.string.exception_tango_invalid), e);
                    }
                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSurfaceView.onPause();
        mIsTangoPoseReady.compareAndSet(true, false);
        // Synchronize against disconnecting while the service is being used in the OpenGL thread or
        // in the UI thread.
        // NOTE: DO NOT lock against this same object in the Tango callback thread. Tango.disconnect
        // will block here until all Tango callback calls are finished. If you lock against this
        // object in a Tango callback thread it will cause a deadlock.
        synchronized (this) {
            try {
                mTango.disconnect();
            } catch (TangoErrorException e) {
                Log.e(TAG, getString(R.string.exception_tango_error), e);
            }
        }
    }

    /**
     * Sets up the tango configuration object. Make sure mTango object is initialized before
     * making this call.
     */
    private TangoConfig setupTangoConfig(Tango tango) {
        // Create a new Tango Configuration and enable the MotionTrackingActivity API.
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
        //开启追踪功能
        config.putBoolean(TangoConfig.KEY_BOOLEAN_MOTIONTRACKING, true);
        // Tango service should automatically attempt to recover when it enters an invalid state.
        //自动恢复
        config.putBoolean(TangoConfig.KEY_BOOLEAN_AUTORECOVERY, true);
        return config;
    }

    /**
     * Set up the callback listeners for the Tango service and obtain other parameters required
     * after Tango connection.
     * Listen to pose data.
     */
    private void startupTango() {
        // Select coordinate frame pair.
        //TangoCoordinateFramePair(int base, int target)
        //设置坐标系
        ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<TangoCoordinateFramePair>();
        framePairs.add(new TangoCoordinateFramePair(
                TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,//Origin when the device started tracking.当设备开始跟踪时的来源。
                TangoPoseData.COORDINATE_FRAME_DEVICE));//Device coordinate frame. 设备坐标系

        // Listen for new Tango data.
        //通过回调获取和处理坐标数据
        mTango.connectListener(framePairs, new Tango.OnTangoUpdateListener() {
            @Override
            public void onPoseAvailable(final TangoPoseData pose) {
                synchronized (MotionTrackingActivity.this) {
                    // When we receive the first onPoseAvailable callback, we now the Tango has
                    // located itself.
                    mIsTangoPoseReady.compareAndSet(false, true);
                }
            }

            @Override
            public void onXyzIjAvailable(TangoXyzIjData xyzIj) {
                // We are not using onXyzIjAvailable for this app.
            }

            @Override
            public void onPointCloudAvailable(final TangoPointCloudData pointCloudData) {
                // We are not using onPointCloudAvailable for this app.
            }

            @Override
            public void onTangoEvent(final TangoEvent event) {
                // Ignoring TangoEvents.
            }

            @Override
            public void onFrameAvailable(int cameraId) {
                // We are not using onFrameAvailable for this application.
            }
        });
    }

    /**
     * Connects the Rajawali surface view and its renderer. This is ideally called only once in
     * onCreate.
     */
    private void setupRenderer() {
        mSurfaceView.setEGLContextClientVersion(2);
        mRenderer.getCurrentScene().registerFrameCallback(new ASceneFrameCallback() {
            @Override
            public void onPreFrame(long sceneTime, double deltaTime) {
                // If the Tango has not located itself, we won't do anything, since we can't get a
                // valid pose.
                if (!mIsTangoPoseReady.get()) {
                    return;
                }

                // Synchronize to avoid concurrent access from the Tango callback thread below.
                synchronized (MotionTrackingActivity.this) {
                    // Update the scene objects with the latest device position and orientation
                    // information.
                    try {
                        //Calculate the transform from target frame to base frame of reference represented in the specified engine coordinate system.
                        //计算从目标框架到指定引擎坐标系中表示的基准参考框架的变换。
                        //timestamp - The time to use for the corresponding transform query.
                        //baseFrame - The Tango device coordinate frame the pose is converting to.
                        //targetFrame - The Tango device coordinate frame the pose is converting from.
                        //engine - The engine being used to render the augmented/virtual reality data. Can be OpenGl, Unity, or Tango.
                        //rotationIndex - The index of the display rotation between display's default (natural) orientation and current orientation.*/
                        TangoPoseData pose =
                                TangoSupport.getPoseAtTime(0.0,
                                        TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                                        TangoPoseData.COORDINATE_FRAME_DEVICE,
                                        TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                                        mCurrentDisplayOrientation);

                        if (pose.statusCode == TangoPoseData.POSE_VALID) {
                            // Update the camera pose from the renderer
                            mRenderer.updateRenderCameraPose(pose);
                        }
                    } catch (TangoErrorException e) {
                        Log.e(TAG, "TangoSupport.getPoseAtTime error", e);
                    }
                }
            }

            @Override
            public void onPreDraw(long sceneTime, double deltaTime) {

            }

            @Override
            public void onPostFrame(long sceneTime, double deltaTime) {

            }

            @Override
            public boolean callPreFrame() {
                return true;
            }
        });

        mSurfaceView.setSurfaceRenderer(mRenderer);
        // Set render mode to RENDERMODE_CONTINUOUSLY.
        mSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }
}
