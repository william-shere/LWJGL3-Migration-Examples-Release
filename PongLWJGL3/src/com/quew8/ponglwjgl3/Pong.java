package com.quew8.ponglwjgl3;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import org.lwjgl.BufferUtils;
import static org.lwjgl.glfw.GLFW.*;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 *
 * @author Quew8
 */
public class Pong {
    public static final Colour 
            BLACK = new Colour(0, 0, 0), 
            WHITE = new Colour(1, 1, 1),
            GREY = new Colour(0.5f, 0.5f, 0.5f),
            ORANGE = new Colour(1, 0.55f, 0),
            RED = new Colour(1, 0, 0);
    /**
     * The initial width and height of the window.
     */
    public static final int WINDOW_WIDTH = 1000, WINDOW_HEIGHT = 600;
    /**
     * Constants regarding dimensions in game.
     */
    public static final float SCREEN_WIDTH = 5, SCREEN_HEIGHT = 3,
            PADDLE_WIDTH = 0.1f, PADDLE_HEIGHT = 1, 
            BALL_RADIUS = 0.1f;
    /**
     * Speed of the paddle in screen units/ms.
     */
    public static final float PADDLE_SPEED = 0.005f;
    /**
     * Ratio of distance dragged to speed.
     */
    public static final float BALL_SPEED_SCALE = 0.005f;
    /**
     * Number of vertices to display circle with.
     */
    public static final int BALL_N_VERTICES = 16;
    /**
     * Colours to draw various elements in.
     */
    public static final Colour PADDLE_COLOUR = WHITE, 
            BALL_COLOUR = WHITE,
            BORDER_COLOUR = GREY,
            LINE_COLOUR = WHITE;
    /**
     * The left and right paddle.
     */
    private final Paddle paddle1 = new Paddle(Side.LEFT), paddle2 = new Paddle(Side.RIGHT);
    /**
     * List of active balls.
     */
    private final ArrayList<Ball> balls = new ArrayList<>();
    {
        //Add random initial ball.
        float vx = (float) ((Math.random() * 0.002f) + 0.002f);
        if(Math.random() >= 0.5f) {
            vx = -vx;
        }
        float vy = (float) ((Math.random() * 0.002f)) - 0.001f;
        balls.add(new Ball(SCREEN_WIDTH / 2, SCREEN_HEIGHT / 2, vx, vy));
    };
    /**
     * Should start in fullscreen mode.
     */
    private static final boolean START_FULLSCREEN = true;
    /**
     * A reference to the error callback so it doesn't get GCd.
     */
    private GLFWErrorCallback errorCallback;
    /**
     * A reference to the framebuffer size callback.
     */
    private GLFWFramebufferSizeCallback framebufferSizeCallback;
    /**
     * The handle of the window.
     */
    private long window;
    /**
     * Has there been a close request not coming from the window itself.
     */
    private boolean remainOpen = true;
    /**
     * Wrapper for the framebuffer dimensions. For transforming
     * mouse click coords.
     */
    private final Framebuffer framebuffer = new Framebuffer();
    /**
     * Wrapper for the orthographic projection currently used. For transforming
     * mouse click coords.
     */
    private final Projection projection = new Projection();
    /**
     * The coords at which the to-be-added ball started. Used for velocity calculation.
     */
    private float centreX, centreY;
    /**
     * The to-be-added ball currently being dragged.
     */
    private Ball addBall = null;
    /**
     * The current game state. PLAYING, PAUSED or LOST.
     */
    private State currentState = State.PLAYING;
    /**
     * The time of the start of the last loop. Used for delta time calculation.
     */
    private double lastTime;
    /**
     * Shaders.
     */
    private final String vertexSrc =
            "#version 330\n"
            + "layout(std140) uniform mat4 projection;\n"
            + "layout(std140) uniform mat4 modelView;\n"
            + "layout(location = 0) in vec2 position;\n"
            + "layout(location = 1) in vec3 colour;\n"
            + "varying vec3 vColour;"
            + "void main(void) {\n"
            + "    vColour = colour;"
            + "    gl_Position = projection * modelView * vec4(position.xy, 0, 1);\n"
            + "}\n";
    private final String fragmentSrc =
            "#version 330\n"
            + "varying vec3 vColour;"
            + "layout(location = 0) out vec4 colourOut;\n"
            + "void main(void) {\n"
            + "    colourOut = vec4(vColour.rgb, 1);\n"
            + "}\n";
    /**
     * OpenGL object handles.
     */
    private int program, vao, vbo;
    /**
     * The location and a buffer representing the modelViewMatrix uniform.
     */
    private int modelViewLoc;
    private FloatBuffer modelViewMatrix;
    /**
     * The location and a buffer representing the projectionMatrix uniform.
     */
    private int projectionLoc;
    private FloatBuffer projectionMatrix;
    /**
     * Reference for the vertex data of various scene objects.
     */
    private RenderHandle paddleHandle, ballHandle, boundsHandle, lineHandle;
    /**
     * A buffer for transferring the data for a new line.
     */
    private FloatBuffer replaceBuffer;
    
    public void init() {
        //Initialize GLFW.
        glfwInit();
        //Setup an error callback to print GLFW errors to the console.
        glfwSetErrorCallback(errorCallback = GLFWErrorCallback.createPrint(System.err));
        
        //Set resizable
        glfwWindowHint(GLFW_RESIZABLE, GL_TRUE);
        //Request an OpenGL 3.3 Core context.
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        //Create the window with the specified title.
        window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, "Pong - LWJGL3", 0, 0);
        if(window == 0) {
            throw new RuntimeException("Failed to create window");
        }
        //Make this window's context the current on this thread.
        glfwMakeContextCurrent(window);
        //Let LWJGL know to use this current context.
        GL.createCapabilities();
        
        initGL();
        
        //Setup the framebuffer resize callback.
        glfwSetFramebufferSizeCallback(window, (framebufferSizeCallback = new GLFWFramebufferSizeCallback() {

            @Override
            public void invoke(long window, int width, int height) {
                onResize(width, height);
            }
            
        }));
        onResize(WINDOW_WIDTH, WINDOW_HEIGHT);
        
        //Make this window visible.
        glfwShowWindow(window);
        
        //For the first frame, take this time to be the last frame's start.
        lastTime = currentTimeMillis();
    }
    
    /**
     * Initializes the OpenGL state. Creating programs, VAOs and VBOs and sets 
     * appropriate state. 
     */
    public void initGL() {
        program = glCreateProgram();
        int vertexId = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexId, vertexSrc);
        glCompileShader(vertexId);
        if(glGetShaderi(vertexId, GL_COMPILE_STATUS) != GL_TRUE) {
            System.out.println(glGetShaderInfoLog(vertexId, Integer.MAX_VALUE));
            throw new RuntimeException();
        }
        
        int fragmentId = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentId, fragmentSrc);
        glCompileShader(fragmentId);
        if(glGetShaderi(fragmentId, GL_COMPILE_STATUS) != GL_TRUE) {
            System.out.println(glGetShaderInfoLog(fragmentId, Integer.MAX_VALUE));
            throw new RuntimeException();
        }
        
        glAttachShader(program, vertexId);
        glAttachShader(program, fragmentId);
        glLinkProgram(program);
        if(glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            System.out.println(glGetProgramInfoLog(program, Integer.MAX_VALUE));
            throw new RuntimeException();
        }
        
        modelViewLoc = glGetUniformLocation(program, "modelView");
        if(modelViewLoc == -1) {
            throw new RuntimeException();
        }
        modelViewMatrix = BufferUtils.createFloatBuffer(16);
        projectionLoc = glGetUniformLocation(program, "projection");
        if(projectionLoc == -1) {
            throw new RuntimeException();
        }
        projectionMatrix = BufferUtils.createFloatBuffer(16);
        
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer fb = BufferUtils.createFloatBuffer(5 * (4 + BALL_N_VERTICES + 4 + 2));
        
        fb.put(new float[]{
            0,            0,             PADDLE_COLOUR.red, PADDLE_COLOUR.green, PADDLE_COLOUR.blue,
            PADDLE_WIDTH, 0,             PADDLE_COLOUR.red, PADDLE_COLOUR.green, PADDLE_COLOUR.blue,
            PADDLE_WIDTH, PADDLE_HEIGHT, PADDLE_COLOUR.red, PADDLE_COLOUR.green, PADDLE_COLOUR.blue,
            0,            PADDLE_HEIGHT, PADDLE_COLOUR.red, PADDLE_COLOUR.green, PADDLE_COLOUR.blue
        });
        paddleHandle = new RenderHandle(0, 4);
        
        double step = (Math.PI * 2d) / BALL_N_VERTICES;
        for(int i = 0; i < BALL_N_VERTICES; i++) {
            double theta = i * step;
            float x = (float) (BALL_RADIUS * Math.cos(theta));
            float y = (float) (BALL_RADIUS * Math.sin(theta));
            fb.put(new float[]{
                x, y, BALL_COLOUR.red, BALL_COLOUR.green, BALL_COLOUR.blue
            });
        }
        ballHandle = new RenderHandle(4, BALL_N_VERTICES);
        
        fb.put(new float[]{
            0,            0,             BORDER_COLOUR.red, BORDER_COLOUR.green, BORDER_COLOUR.blue,
            SCREEN_WIDTH, 0,             BORDER_COLOUR.red, BORDER_COLOUR.green, BORDER_COLOUR.blue,
            SCREEN_WIDTH, SCREEN_HEIGHT, BORDER_COLOUR.red, BORDER_COLOUR.green, BORDER_COLOUR.blue,
            0,            SCREEN_HEIGHT, BORDER_COLOUR.red, BORDER_COLOUR.green, BORDER_COLOUR.blue
        });
        boundsHandle = new RenderHandle(4 + BALL_N_VERTICES, 4);
        
        fb.put(new float[]{
            0,            0,             LINE_COLOUR.red, LINE_COLOUR.green, LINE_COLOUR.blue,
            SCREEN_WIDTH, SCREEN_HEIGHT, LINE_COLOUR.red, LINE_COLOUR.green, LINE_COLOUR.blue
        });
        lineHandle = new RenderHandle(4 + BALL_N_VERTICES + 4, 2);
        
        fb.flip();
        glBufferData(GL_ARRAY_BUFFER, fb, GL_STATIC_DRAW);
        
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 20, 0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 20, 8);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        
        glBindVertexArray(0);
        
        setBackColour();
        glLineWidth(5);
        
        checkError();
    }
    
    /**
     * Loops game, rendering and updating until close requested.
     * 
     */
    public void loop() {
        //Continue whilst no close request from internal nor external.
        while(glfwWindowShouldClose(window) == GL_FALSE && remainOpen) {
            //Calculate delta time
            double thisTime = currentTimeMillis();
            double delta = thisTime - lastTime;
            lastTime = thisTime;
            
            update(delta);
            render();
            
            //Polls input.
            glfwPollEvents();
            //Swaps framebuffers.
            glfwSwapBuffers(window);
        }
    }
    
    /**
     * Updates the game for a frame, handling input, updating paddles and balls
     * and handling framebuffer resize events.
     * 
     * @param delta The Time difference in milliseconds since the last frame.
     */
    public void update(double delta) {
        /*//Iterate through mouse input events.
        while(Mouse.next()) {
            //If this event is primary mouse button.
            if(Mouse.getEventButton() == 0) {
                //If this event is down event and no current to-add-ball.
                //Else If this event is up event and there is a current to-add-ball.
                if(Mouse.getEventButtonState() && addBall == null) {
                    onNewBall(Mouse.getEventX(), Mouse.getEventY());
                } else if(!Mouse.getEventButtonState() && addBall != null) {
                    onNewBallRelease(Mouse.getEventX(), Mouse.getEventY());
                }
            }
        }
        //If there is a current to-add-ball. (Mouse movement not event driven in LWJGL2)
        if(addBall != null) {
            updateNewBall(Mouse.getX(), Mouse.getY());
        }
        
        //Iterate through keyboard events.
        while(Keyboard.next()) {
            //If current event key is Space and is up event.
            //Else If current event key is F5 and is key up event.
            //Else If current event key is Escape and is key up event.
            if(Keyboard.getEventKey() == Keyboard.KEY_SPACE && !Keyboard.getEventKeyState()) {
                onPlayPauseToggle();
            } else if(Keyboard.getEventKey() == Keyboard.KEY_F5 && !Keyboard.getEventKeyState()) {
                setDisplayMode(true);
            } else if(Keyboard.getEventKey() == Keyboard.KEY_ESCAPE && !Keyboard.getEventKeyState()) {
                //Request close.
                remainOpen = false;
            }
        }
        //If display is resized or has gone fullscreen (leaving fullscreen 
        //causes resize event anyway) then framebuffer has resized and needs 
        //update.
        //If not paused then update paddles.
        if(currentState == State.PLAYING || currentState == State.LOST) {
            updatePaddle(paddle1, delta, Keyboard.isKeyDown(Keyboard.KEY_W), Keyboard.isKeyDown(Keyboard.KEY_S));
            updatePaddle(paddle2, delta, Keyboard.isKeyDown(Keyboard.KEY_UP), Keyboard.isKeyDown(Keyboard.KEY_DOWN));

        }
        //If playing then update balls.
        if(currentState == State.PLAYING) {
            for(Ball b: balls) {
                if(!updateBall(b, delta)) {
                    break;
                }
            }
        }*/
    }
    
    /**
     * Clears the screen and renders all scene objects.
     */
    public void render() {
        glClear(GL_COLOR_BUFFER_BIT);

        glUseProgram(program);
        glBindVertexArray(vao);
        
        glUniformMatrix4fv(projectionLoc, false, projectionMatrix);
        
        drawHandleLinesAt(boundsHandle, 0, 0);
        drawHandleAt(paddleHandle, paddle1.getX(), paddle1.y);
        drawHandleAt(paddleHandle, paddle2.getX(), paddle2.y);
        balls.stream().forEach((b) -> {
            drawHandleAt(ballHandle, b.x, b.y);
        });
        if(addBall != null) {
            drawHandleAt(ballHandle, addBall.x, addBall.y);
            drawHandleLinesAt(lineHandle, 0, 0);
        }
        
        glBindVertexArray(0);
        glUseProgram(0);
        
        checkError();
    }
    
    /**
     * Releases game resources and window.
     */
    public void deinit() {
        deinitGL();
        glfwDestroyWindow(window);   
        glfwTerminate();
    }
    
    /**
     * Releases in use OpenGL resources.
     */
    public void deinitGL() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteProgram(program);
    }
    
    /**
     * Draws the specified handle at the specified position using lines. The appropriate 
     * VAO and program must be bound.
     * 
     * @param handle The handle to draw.
     * @param x The x position.
     * @param y The y position.
     */
    public void drawHandleLinesAt(RenderHandle handle, float x, float y) {
        setTranslation(modelViewMatrix, x, y);
        glUniformMatrix4fv(modelViewLoc, false, modelViewMatrix);
        glDrawArrays(GL_LINE_LOOP, handle.first, handle.count);
    }
    
    /**
     * Draws the specified handle at the specified position. The appropriate 
     * VAO and program must be bound.
     * 
     * @param handle The handle to draw.
     * @param x The x position.
     * @param y The y position.
     */
    public void drawHandleAt(RenderHandle handle, float x, float y) {
        setTranslation(modelViewMatrix, x, y);
        glUniformMatrix4fv(modelViewLoc, false, modelViewMatrix);
        glDrawArrays(GL_TRIANGLE_FAN, handle.first, handle.count);
    }
    
    /**
     * Updates the buffer location specified by the handle with data representing 
     * a line going from (@code x0, @code y0) to (@code x1, @code y1).
     * 
     * @param handle The buffer location to update.
     * @param x0 The initial x coordinate.
     * @param y0 The initial y coordinate.
     * @param x1 The final x coordinate.
     * @param y1 The final y coordinate.
     */
    public void setLineFromTo(RenderHandle handle, float x0, float y0, float x1, float y1) {
        if(replaceBuffer == null) {
            replaceBuffer = BufferUtils.createFloatBuffer(4 * 5);
        }
        replaceBuffer.put(new float[]{
            x0, y0, LINE_COLOUR.red, LINE_COLOUR.green, LINE_COLOUR.blue,
            x1, y1, LINE_COLOUR.red, LINE_COLOUR.green, LINE_COLOUR.blue
        });
        replaceBuffer.flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, handle.first * 5 * 4, replaceBuffer);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
    
    /**
     * Sets the appropriate display mode based on whether the display is in 
     * fullscreen mode or not. Uses desktop display mode for fullscreen mode.
     * 
     * @param toggle Toggle the fullscreen setting before setting the display mode.
     */
    public void setDisplayMode(boolean toggle) {
        /*if(Display.isFullscreen() ^ toggle) {
            goneFullscreen = true;
            Display.setDisplayModeAndFullscreen(Display.getDesktopDisplayMode());
        } else {
            Display.setDisplayMode(new DisplayMode(WINDOW_WIDTH, WINDOW_HEIGHT));
        }*/
    }
    
    /**
     * Updates the specified paddle.
     * 
     * @param p The paddle to update.
     * @param delta The delta time.
     * @param up Is this paddle's "up" control down.
     * @param down Is this paddle's "down" control down.
     */
    public void updatePaddle(Paddle p, double delta, boolean up, boolean down) {
        if(up) {
            p.y += delta * PADDLE_SPEED;
        }
        if(down) {
            p.y -= delta * PADDLE_SPEED;
        }
        p.y = Math.max(0, Math.min(SCREEN_HEIGHT - PADDLE_HEIGHT, p.y));
    }
    
    /**
     * To be called when the mouse is pressed to create a new ball.
     * 
     * @param windowX The window x coordinate of the mouse.
     * @param windowY The window y coordinate of the mouse.
     */
    public void onNewBall(int windowX, int windowY) {
        centreX = windowToWorldCoordsX(framebuffer, projection, windowX);
        centreY = windowToWorldCoordsY(framebuffer, projection, windowY);
        addBall = new Ball(centreX, centreY, 0, 0);
    }
    
    /**
     * To be called when the mouse is moved whilst there is a new ball.
     * 
     * @param windowX The window x coordinate of the mouse.
     * @param windowY The window y coordinate of the mouse.
     */
    public void updateNewBall(int windowX, int windowY) {
        addBall.x = windowToWorldCoordsX(framebuffer, projection, windowX);
        addBall.y = windowToWorldCoordsY(framebuffer, projection, windowY);
        setLineFromTo(lineHandle, centreX, centreY, addBall.x, addBall.y);
    }
    
    /**
     * To be called when the mouse is released whilst there is a new ball to 
     * finalize this balls velocity and release it.
     * 
     * @param windowX The window x coordinate of the mouse.
     * @param windowY The window y coordinate of the mouse.
     */
    public void onNewBallRelease(int windowX, int windowY) {
        addBall.vx = (centreX - addBall.x) * BALL_SPEED_SCALE;
        addBall.vy = (centreY - addBall.y) * BALL_SPEED_SCALE;
        balls.add(addBall);
        addBall = null;
        onRestart();
    }
    
    /**
     * Updates a ball, returns false if the ball is out of the game.
     * 
     * @param b The ball to update.
     * @param delta The delta time.
     * @return false if the ball is out of play.
     */
    public boolean updateBall(Ball b, double delta) {
        b.x += b.vx * delta;
        b.y += b.vy * delta;
        if(b.x + BALL_RADIUS < 0) {
            onLost(Side.RIGHT);
            return false;

        }
        if(b.x - BALL_RADIUS > SCREEN_WIDTH) {
            onLost(Side.RIGHT);
            return false;
        }
        if(b.vy > 0 && b.y + BALL_RADIUS > SCREEN_HEIGHT) {
            b.vy = -b.vy;
        } else if(b.vy < 0 && b.y - BALL_RADIUS < 0) {
            b.vy = -b.vy;
        }
        if(paddleIntersectingBall(paddle1, b)) {
            b.vx = -b.vx;
        } else if(paddleIntersectingBall(paddle2, b)) {
            b.vx = -b.vx;
        }
        return true;
    }
    
    /**
     * To be called when a play/pause toggle is requested.
     */
    public void onPlayPauseToggle() {
        switch(currentState) {
            case PLAYING: onPause(); break;
            case PAUSED: onPlay(); break;
            case LOST: break;
        }
    }
    
    /**
     * To be called when the game transitions from paused to playing.
     */
    public void onPlay() {
        currentState = State.PLAYING;
        setBackColour();
    }
    
    /**
     * To be called when the game transitions from playing to paused.
     */
    public void onPause() {
        currentState = State.PAUSED;
        setBackColour();
    }
    
    /**
     * To be called when the game transitions from lost to playing.
     */
    public void onRestart() {
        currentState = State.PLAYING;
        setBackColour();
    }
    
    /**
     * To be called when the game transitions from playing to lost.
     * 
     * @param side The side on which the ball was lost. (unused)
     */
    public void onLost(Side side) {
        currentState = State.LOST;
        balls.clear();
        setBackColour();
    }
    
    /**
     * Sets the appropriate back colour based on the game's current state.
     */
    public void setBackColour() {
        glClearColor(currentState.backColour.red, currentState.backColour.green, currentState.backColour.blue, 0);
    }
    
    /**
     * To be called when the game's framebuffer is resized. Updates the projection
     * matrix.
     * 
     * @param framebufferWidth The width of the new framebuffer
     * @param framebufferHeight  The height of the new framebuffer
     */
    public void onResize(int framebufferWidth, int framebufferHeight) {
        framebuffer.width = framebufferWidth;
        framebuffer.height = framebufferHeight;
        float aspectRatio = (float) framebufferHeight / framebufferWidth;
        float desiredAspectRatio = SCREEN_HEIGHT / SCREEN_WIDTH;
        projection.left = 0;
        projection.right = SCREEN_WIDTH;
        projection.bottom = 0;
        projection.top = SCREEN_HEIGHT;
        if(aspectRatio == desiredAspectRatio) {
        } else if(aspectRatio > desiredAspectRatio) {
            float newScreenHeight = SCREEN_WIDTH * aspectRatio;
            projection.bottom = -(newScreenHeight - SCREEN_HEIGHT) / 2f;
            projection.top = newScreenHeight + projection.bottom;
        } else if(aspectRatio < desiredAspectRatio) {
            float newScreenWidth = SCREEN_HEIGHT / aspectRatio;
            projection.left = -(newScreenWidth - SCREEN_WIDTH) / 2f;
            projection.right = newScreenWidth + projection.left;
        }
        setOrtho2D(projectionMatrix, projection);
        glViewport(0, 0, framebufferWidth, framebufferHeight);
    }
    
    /**
     * Utility method to check if a ball intersects a paddle's leading edge.
     * 
     * @param p The paddle.
     * @param b The ball.
     * @return 
     */
    public static boolean paddleIntersectingBall(Paddle p, Ball b) {
        if((p.side == Side.LEFT && b.vx > 0) || (p.side == Side.RIGHT && b.vx < 0)) {
            return false;
        }
        float edgeX = p.side == Side.LEFT ? PADDLE_WIDTH : SCREEN_WIDTH - PADDLE_WIDTH;
        if(b.y >= p.y && b.y <= p.y + PADDLE_HEIGHT) {
            return Math.abs(b.x - edgeX) <= BALL_RADIUS;
        } else if(Math.pow(b.y - p.y, 2) + Math.pow(b.x - edgeX, 2) <= Math.pow(BALL_RADIUS, 2)) {
            return true;
        } else if(Math.pow(b.y - (p.y + PADDLE_HEIGHT), 2) + Math.pow(b.x - edgeX, 2) <= Math.pow(BALL_RADIUS, 2)) {
            return true;
        }
        return false;
    }
    
    /**
     * Returns the current system time in milliseconds.
     * 
     * @return The current system time in milliseconds.
     */
    public static double currentTimeMillis() {
        return glfwGetTime() * 1000;
    }
    
    /**
     * Utility method which checks for an OpenGL error, throwing an exception if
     * one is found.
     */
    public static void checkError() {
        int err = glGetError();
        switch(err) {
            case GL_NO_ERROR: return;
            case GL_INVALID_OPERATION: throw new RuntimeException("Invalid Operation");
            case GL_INVALID_ENUM: throw new RuntimeException("Invalid Enum");
            case GL_INVALID_VALUE: throw new RuntimeException("Invalid Value");
            case GL_INVALID_FRAMEBUFFER_OPERATION: throw new RuntimeException("Invalid Framebuffer Operation");
            case GL_OUT_OF_MEMORY: throw new RuntimeException("Out of Memory");
        }
    }
    
    /**
     * Sets the contents of the specified buffer to the identity matrix.
     * 
     * @param dest The buffer to set
     */
    public static void setIdentity(FloatBuffer dest) {
        dest.put(new float[] {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        });
        dest.flip();
    }
    
    /**
     * Sets the contents of the specified buffer to a translation matrix.
     * 
     * @param dest The buffer to set.
     * @param dx The x translation.
     * @param dy The y translation.
     */
    public static void setTranslation(FloatBuffer dest, float dx, float dy) {
        dest.put(new float[] {
            1,  0,  0, 0,
            0,  1,  0, 0,
            0,  0,  1, 0,
            dx, dy, 0, 1
        });
        dest.flip();
    }
    
    /**
     * Sets the contents of the specified buffer to an orthographic projection matrix.
     * 
     * @param dest The buffer to set.
     * @param p The projection to use.
     */
    public static void setOrtho2D(FloatBuffer dest, Projection p) {
        float f1 = p.right - p.left;
        float f2 = p.top - p.bottom;
        dest.put(new float[]{
            2f / f1,                  0,                        0,  0,
            0,                        2f / f2,                  0,  0,
            0,                        0,                        -1, 0,
            -(p.right + p.left) / f1, -(p.top + p.bottom) / f2, 0,  1
        });
        dest.flip();
    }
    
    /**
     * Utility method to convert window coords to world coords.
     * 
     * @param framebuffer The window's framebuffer
     * @param proj The in use projection
     * @param windowX The x value in window coords.
     * @return The x value in world coords.
     */
    public static float windowToWorldCoordsX(Framebuffer framebuffer, Projection proj, int windowX) {
        return (((float) windowX / framebuffer.width) * (proj.right - proj.left)) + proj.left;
    }
    
    /**
     * Utility method to convert window coords to world coords.
     * 
     * @param framebuffer The window's framebuffer
     * @param proj The in use projection
     * @param windowY The y value in window coords.
     * @return The y value in world coords.
     */
    public static float windowToWorldCoordsY(Framebuffer framebuffer, Projection proj, int windowY) {
        return (((float) windowY / framebuffer.height) * (proj.top - proj.bottom)) + proj.bottom;
    }
    
    /**
     * A struct representing a framebuffer.
     */
    public static class Framebuffer {
        int width, height;
    }
    
    /**
     * A struct representing an orthographic projection.
     */
    public static class Projection {
        float left, right, bottom, top;
    }
    
    /**
     * A struct representing an object in an OpenGL buffer.
     */
    public static class RenderHandle {
        final int first, count;

        public RenderHandle(int first, int count) {
            this.first = first;
            this.count = count;
        }
    }
    
    /**
     * A struct representing a colour.
     */
    public static class Colour {
        final float red, green, blue;

        public Colour(float red, float green, float blue) {
            this.red = red;
            this.green = green;
            this.blue = blue;
        }
    }
    
    /**
     * An enum encompassing the game state.
     */
    public static enum State {
        PLAYING(BLACK), 
        PAUSED(ORANGE), 
        LOST(RED);

        private State(Colour backColour) {
            this.backColour = backColour;
        }
        
        /**
         * The back colour associated with this state.
         */
        final Colour backColour;
    }
    
    /**
     * A class representing a paddle.
     */
    public static class Paddle {
        /**
         * The side on which this paddle resides.
         */
        final Side side;
        /**
         * The y coord of the bottom of the paddle.
         */
        float y;

        public Paddle(Side side) {
            this.side = side;
            this.y = 0;
        }
        
        /**
         * Returns the x coord of the left of the paddle.
         * 
         * @return the x coord of the left of the paddle.
         */
        public float getX() {
            switch(side) {
                case LEFT: return 0;
                case RIGHT: return SCREEN_WIDTH - PADDLE_WIDTH;
                default: throw new IllegalStateException("Invalid Enum");
            }
        }
    }
    
    /**
     * A struct representing a ball.
     */
    public static class Ball {
        /**
         * The current x, y coords of the ball.
         */
        float x, y;
        /**
         * The current x, y velocity of the ball.
         */
        float vx, vy;

        public Ball(float x, float y, float vx, float vy) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
        }
    }
    
    /**
     * 
     */
    public static enum Side {
        LEFT, RIGHT;
    }
    
    /**
     * @param args the command line arguments
     * @throws java.io.IOException
     */
    public static void main(String[] args) throws IOException {
        String[] natives = new String[] {
            "glfw.dll",
            "glfw32.dll",
            "jemalloc.dll",
            "jemalloc32.dll",
            "libglfw.dylib",
            "libglfw.so",
            "libglfw32.so",
            "libjemalloc.dylib",
            "libjemalloc.so",
            "libjemalloc32.so",
            "liblwjgl.dylib",
            "liblwjgl.so",
            "liblwjgl32.so",
            "lwjgl.dll",
            "lwjgl32.dll"
        };
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        byte[] buff = new byte[2048];
        for(String file: natives) {
            InputStream is = Pong.class.getResourceAsStream(file);
            File temp = new File(tmpDir, file);
            try(FileOutputStream os = new FileOutputStream(temp)) {
                int read;
                while((read = is.read(buff)) != -1) {
                    os.write(buff, 0, read);
                }
            }
        }
        System.setProperty("org.lwjgl.librarypath", tmpDir.getAbsolutePath());
        Pong p = new Pong();
        p.init();
        p.loop();
        p.deinit();
    }
    
}
