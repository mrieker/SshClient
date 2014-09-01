package com.outerworldapps.sshclient;

import android.content.Context;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/**
 * Handle panning and zooming a display.
 * Call OnTouchEvent() with incoming mouse events.
 * Implement MouseDown(), MouseUp(), Panning() and Scaling().
 */
public abstract class PanAndZoom implements ScaleGestureDetector.OnScaleGestureListener {
	public  static final int panningblocktime = 200;

	private Context ctx;
	private float mouse0LastX;
	private float mouse0LastY;
	private long blockPanUntil;
	private ScaleGestureDetector scaleGestureDetector;

	public PanAndZoom (Context ctx)
	{
		this.ctx = ctx;
		scaleGestureDetector = new ScaleGestureDetector (ctx, this);
	}

	// called when mouse pressed
	//  x,y = absolute mouse position
	public abstract void MouseDown (float x, float y);

	// called when mouse released
	public abstract void MouseUp ();

	// called when panning
	//  x,y = absolute mouse position
	//  dx,dy = delta position
	public abstract void Panning (float x, float y, float dx, float dy);

	// called when scaling
	//  fx,fy = absolute position of center of scaling
	//  sf = delta scaling factor
	public abstract void Scaling (float fx, float fy, float sf);

	/**
	 * @brief Callback for mouse events on the image.
	 */
	public boolean OnTouchEvent (MotionEvent event)
	{
		/*
		 * Maybe scaling is being performed.
		 * Note: scaleGestureDetector.onTouchEvent() always return true,
		 *       so scaleGestureDetector.isInProgress() must be called.
		 */
		try {
			scaleGestureDetector.onTouchEvent (event);
			if (scaleGestureDetector.isInProgress ()) {
				// block panning for a little time after done scaling
				// to eliminate surious jumps at end of scaling
				blockPanUntil  = System.currentTimeMillis () + panningblocktime;
				return true;
			}
		} catch (ArrayIndexOutOfBoundsException aioobe) {
			Log.w ("PanAndZoom", "onTouchEvent: scaleGestureDetector.onTouchEvent()", aioobe);
			scaleGestureDetector = new ScaleGestureDetector (ctx, this);
		}

		/*
		 * Maybe translation is being performed.
		 */
		switch (event.getAction ()) {
			case MotionEvent.ACTION_DOWN: {
				mouse0LastX = event.getX ();
				mouse0LastY = event.getY ();
				MouseDown (mouse0LastX, mouse0LastY);
				break;
			}
			case MotionEvent.ACTION_MOVE: {
				float x = event.getX ();
				float y = event.getY ();

				// scaling blocks panning for a few milliseconds
				// to eliminate spurious mouse panning events when
				// exiting scaling mode
				if (System.currentTimeMillis () >= blockPanUntil) {
					Panning (x, y, x - mouse0LastX, y - mouse0LastY);
				}

				mouse0LastX = x;
				mouse0LastY = y;
				break;
			}
			case MotionEvent.ACTION_UP: {
				MouseUp ();
				break;
			}
		}
		return true;
	}

	/**
	 * @brief ScaleGestoreDetector.OnScaleGestureListener implementation methods.
	 */
	@Override
	public boolean onScaleBegin (ScaleGestureDetector detector)
	{
		return true;
	}

	@Override
	public boolean onScale (ScaleGestureDetector detector)
	{
		Scaling (detector.getFocusX (), detector.getFocusY (), detector.getScaleFactor ());
		return true;
	}

	@Override
	public void onScaleEnd (ScaleGestureDetector detector)
	{ }
}
