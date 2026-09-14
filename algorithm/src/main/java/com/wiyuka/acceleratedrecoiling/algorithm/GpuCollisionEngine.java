package com.wiyuka.acceleratedrecoiling.algorithm;

import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_kernel;
import org.jocl.cl_mem;
import org.jocl.cl_platform_id;
import org.jocl.cl_program;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.jocl.CL.CL_CONTEXT_PLATFORM;
import static org.jocl.CL.CL_DEVICE_NAME;
import static org.jocl.CL.CL_DEVICE_TYPE_GPU;
import static org.jocl.CL.CL_MEM_READ_ONLY;
import static org.jocl.CL.CL_MEM_READ_WRITE;
import static org.jocl.CL.CL_MEM_WRITE_ONLY;
import static org.jocl.CL.CL_TRUE;
import static org.jocl.CL.clBuildProgram;
import static org.jocl.CL.clCreateBuffer;
import static org.jocl.CL.clCreateCommandQueueWithProperties;
import static org.jocl.CL.clCreateContext;
import static org.jocl.CL.clCreateKernel;
import static org.jocl.CL.clCreateProgramWithSource;
import static org.jocl.CL.clEnqueueNDRangeKernel;
import static org.jocl.CL.clEnqueueReadBuffer;
import static org.jocl.CL.clEnqueueWriteBuffer;
import static org.jocl.CL.clGetDeviceIDs;
import static org.jocl.CL.clGetDeviceInfo;
import static org.jocl.CL.clGetPlatformIDs;
import static org.jocl.CL.clReleaseCommandQueue;
import static org.jocl.CL.clReleaseContext;
import static org.jocl.CL.clReleaseKernel;
import static org.jocl.CL.clReleaseMemObject;
import static org.jocl.CL.clReleaseProgram;
import static org.jocl.CL.clSetKernelArg;
import static org.jocl.CL.setExceptionsEnabled;

public class GpuCollisionEngine implements CollisionEngine {
    private CollisionConfig config = new CollisionConfig(32, 1, 4, 1, 0);
    private static cl_context context;
    private static cl_program program;
    private static cl_device_id device;
    private static final AtomicLong maxSizeTouched = new AtomicLong(-1);
    private static volatile boolean isInitialized = false;
    private static final double CELL_SIZE = 2.5;
    private static final int TABLE_SIZE = 262139;

    private static final String KERNEL_SOURCE =
            """
                    #pragma OPENCL EXTENSION cl_khr_fp64 : enable
                    
                    #define INVALID_INDEX 4294967295u
                    #define TABLE_SIZE 262139
                                        
                    inline uint hashPosition(double cx, double cy, double cz, double cellSize) {
                        int gx = (int)floor(cx / cellSize);
                        int gy = (int)floor(cy / cellSize);
                        int gz = (int)floor(cz / cellSize);
                        uint h = (abs(gx) * 73856093u) ^ (abs(gy) * 19349663u) ^ (abs(gz) * 83492791u);
                        return h % TABLE_SIZE;
                    }
                    
                    __kernel void compute_hash(__global const double* aabbs, __global uint* hashes, __global uint* indices, int count, double cellSize) {
                        int id = get_global_id(0);
                        if (id >= count) return;
                        double cx = (aabbs[id*6+0] + aabbs[id*6+3]) * 0.5;
                        double cy = (aabbs[id*6+1] + aabbs[id*6+4]) * 0.5;
                        double cz = (aabbs[id*6+2] + aabbs[id*6+5]) * 0.5;
                        hashes[id] = hashPosition(cx, cy, cz, cellSize);
                        indices[id] = id;
                    }
                    
                    __kernel void reset_grid(__global uint* cellStarts, __global uint* cellEnds, int tableSize) {
                        int id = get_global_id(0);
                        if (id >= tableSize) return;
                        cellStarts[id] = INVALID_INDEX;
                        cellEnds[id] = INVALID_INDEX;
                    }
                    
                    __kernel void build_grid(__global const uint* hashes, __global uint* cellStarts, __global uint* cellEnds, int count) {
                        int id = get_global_id(0);
                        if (id >= count) return;
                        uint hash = hashes[id];
                        if (id == 0 || hash != hashes[id - 1]) {
                            cellStarts[hash] = id;
                        }
                        if (id == count - 1 || hash != hashes[id + 1]) {
                            cellEnds[hash] = id + 1;
                        }
                    }
                    
                    __kernel void detect_collisions(
                        __global const double* aabbs, __global const uint* hashes, __global const uint* indices,
                        __global const uint* cellStarts, __global const uint* cellEnds,
                        __global int* outA, __global int* outB, __global float* density, volatile __global int* counter,
                        int count, int maxCollisions, double cellSize
                    ) {
                        int idx = get_global_id(0);
                        if (idx >= count) return;
                    
                        uint real_id = indices[idx];
                        double minX = aabbs[real_id*6+0]; double minY = aabbs[real_id*6+1]; double minZ = aabbs[real_id*6+2];
                        double maxX = aabbs[real_id*6+3]; double maxY = aabbs[real_id*6+4]; double maxZ = aabbs[real_id*6+5];
                    
                        double cx = (minX + maxX) * 0.5;
                        double cy = (minY + maxY) * 0.5;
                        double cz = (minZ + maxZ) * 0.5;
                    
                        int gx = (int)floor(cx / cellSize);
                        int gy = (int)floor(cy / cellSize);
                        int gz = (int)floor(cz / cellSize);
                        int overlapCount = 0;
                    
                        for (int z = -1; z <= 1; z++) {
                            for (int y = -1; y <= 1; y++) {
                                for (int x = -1; x <= 1; x++) {
                                    int nx = gx + x;
                                    int ny = gy + y;
                                    int nz = gz + z;
                                    uint h = (abs(nx) * 73856093u) ^ (abs(ny) * 19349663u) ^ (abs(nz) * 83492791u);
                                    uint neighborHash = h % TABLE_SIZE;
                    
                                    uint start = cellStarts[neighborHash];
                                    if (start == INVALID_INDEX) continue;
                                    uint end = cellEnds[neighborHash];
                    
                                    for (uint i = start; i < end; i++) {
                                        uint other_real_id = indices[i];
                                        if (real_id == other_real_id) continue;
                    
                                        double oMinX = aabbs[other_real_id*6+0]; double oMaxX = aabbs[other_real_id*6+3];
                                        if (minX > oMaxX || maxX < oMinX) continue;
                                        double oMinY = aabbs[other_real_id*6+1]; double oMaxY = aabbs[other_real_id*6+4];
                                        if (minY > oMaxY || maxY < oMinY) continue;
                                        double oMinZ = aabbs[other_real_id*6+2]; double oMaxZ = aabbs[other_real_id*6+5];
                                        if (minZ > oMaxZ || maxZ < oMinZ) continue;
                    
                                        overlapCount++;
                                        if (real_id < other_real_id) {
                                            int out_idx = atomic_inc(counter);
                                            if (out_idx < maxCollisions) {
                                                outA[out_idx] = real_id;
                                                outB[out_idx] = other_real_id;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        density[real_id] = (float)overlapCount; 
                    }
                    """;

    @Override
    public String getName() {
        return "GPU";
    }

    static class PushResultJOCL implements CollisionResult {
        int[] arrA, arrB;
        float[] arrDensity;

        @Override
        public int getA(int index) {
            return arrA[index];
        }

        @Override
        public int getB(int index) {
            return arrB[index];
        }

        @Override
        public float getDensity(int index) {
            return arrDensity[index];
        }

        @Override
        public void copyATo(int[] dest, int length) {
            System.arraycopy(arrA, 0, dest, 0, length);
        }

        @Override
        public void copyBTo(int[] dest, int length) {
            System.arraycopy(arrB, 0, dest, 0, length);
        }

        @Override
        public void copyDensityTo(float[] dest, int length) {
            System.arraycopy(arrDensity, 0, dest, 0, length);
        }
    }

    private static class ThreadState {
        int epoch;
        cl_command_queue commandQueue;
        cl_kernel kComputeHash, kResetGrid, kBuildGrid, kDetect;
        int currentEntityCap = -1, currentCollisionCap = -1;
        int[] cpuHashes, cpuIndices, tempKeys, tempValues;
        cl_mem memAABB, memHashes, memIndices, memOutA, memOutB, memDensity, memCounter;
        cl_mem memCellStarts, memCellEnds;
        final PushResultJOCL resultWrapper = new PushResultJOCL();

        ThreadState() {
            if (!isInitialized) return;

            commandQueue = clCreateCommandQueueWithProperties(context, device, null, null);
            kComputeHash = clCreateKernel(program, "compute_hash", null);
            kResetGrid = clCreateKernel(program, "reset_grid", null);
            kBuildGrid = clCreateKernel(program, "build_grid", null);
            kDetect = clCreateKernel(program, "detect_collisions", null);

            memCellStarts = clCreateBuffer(context, CL_MEM_READ_WRITE, (long) TABLE_SIZE * Sizeof.cl_uint, null, null);
            memCellEnds = clCreateBuffer(context, CL_MEM_READ_WRITE, (long) TABLE_SIZE * Sizeof.cl_uint, null, null);
        }

        private cl_mem releaseMem(cl_mem mem) {
            if (mem != null) clReleaseMemObject(mem);
            return null;
        }

        void reallocBuffers(int entityCount, int maxCollisions) {
            if (entityCount > currentEntityCap) {
                int newCap = (int) (entityCount * 1.5);
                memAABB = releaseMem(memAABB);
                memHashes = releaseMem(memHashes);
                memIndices = releaseMem(memIndices);
                memDensity = releaseMem(memDensity);

                memAABB = clCreateBuffer(context, CL_MEM_READ_ONLY, (long) newCap * 6 * Sizeof.cl_double, null, null);
                memHashes = clCreateBuffer(context, CL_MEM_READ_WRITE, (long) newCap * Sizeof.cl_uint, null, null);
                memIndices = clCreateBuffer(context, CL_MEM_READ_WRITE, (long) newCap * Sizeof.cl_uint, null, null);
                memDensity = clCreateBuffer(context, CL_MEM_WRITE_ONLY, (long) newCap * Sizeof.cl_float, null, null);

                cpuHashes = new int[newCap];
                cpuIndices = new int[newCap];
                tempKeys = new int[newCap];
                tempValues = new int[newCap];
                resultWrapper.arrDensity = new float[newCap];
                currentEntityCap = newCap;
            }

            if (maxCollisions > currentCollisionCap) {
                int newCap = (int) (maxCollisions * 1.5);
                memOutA = releaseMem(memOutA);
                memOutB = releaseMem(memOutB);

                memOutA = clCreateBuffer(context, CL_MEM_WRITE_ONLY, (long) newCap * Sizeof.cl_int, null, null);
                memOutB = clCreateBuffer(context, CL_MEM_WRITE_ONLY, (long) newCap * Sizeof.cl_int, null, null);
                resultWrapper.arrA = new int[newCap];
                resultWrapper.arrB = new int[newCap];
                currentCollisionCap = newCap;
            }
            if (memCounter == null) memCounter = clCreateBuffer(context, CL_MEM_READ_WRITE, Sizeof.cl_int, null, null);
        }

        void destroy() {
            memAABB = releaseMem(memAABB);
            memHashes = releaseMem(memHashes);
            memIndices = releaseMem(memIndices);
            memOutA = releaseMem(memOutA);
            memOutB = releaseMem(memOutB);
            memDensity = releaseMem(memDensity);
            memCounter = releaseMem(memCounter);
            memCellStarts = releaseMem(memCellStarts);
            memCellEnds = releaseMem(memCellEnds);

            if (kComputeHash != null) clReleaseKernel(kComputeHash);
            if (kResetGrid != null) clReleaseKernel(kResetGrid);
            if (kBuildGrid != null) clReleaseKernel(kBuildGrid);
            if (kDetect != null) clReleaseKernel(kDetect);
            if (commandQueue != null) clReleaseCommandQueue(commandQueue);
            kComputeHash = null;
            kResetGrid = null;
            kBuildGrid = null;
            kDetect = null;
            commandQueue = null;
            cpuHashes = null;
            cpuIndices = null;
            tempKeys = null;
            tempValues = null;
            resultWrapper.arrA = null;
            resultWrapper.arrB = null;
            resultWrapper.arrDensity = null;
            currentEntityCap = -1;
            currentCollisionCap = -1;
        }
    }

    private static int threadStateEpoch = 0;
    private static final Set<ThreadState> ALL_THREAD_STATES = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<ThreadState> THREAD_STATE = new ThreadLocal<>();

    private static ThreadState threadState() {
        ThreadState state = THREAD_STATE.get();
        if (state == null || state.epoch != threadStateEpoch) {
            state = new ThreadState();
            state.epoch = threadStateEpoch;
            THREAD_STATE.set(state);
            ALL_THREAD_STATES.add(state);
        }
        return state;
    }

    @Override
    public void initialize() {
        if (isInitialized) return;

        Logger logger = System.getLogger("acceleratedrecoiling.algorithm");
        try {
            setExceptionsEnabled(true);

            int[] numPlatformsArray = new int[1];
            clGetPlatformIDs(0, null, numPlatformsArray);
            if (numPlatformsArray[0] == 0) {
                throw new RuntimeException("No OpenCL platforms found. Please install GPU drivers.");
            }
            cl_platform_id[] platforms = new cl_platform_id[numPlatformsArray[0]];
            clGetPlatformIDs(platforms.length, platforms, null);
            java.util.ArrayList<cl_platform_id> gpuPlatforms = new java.util.ArrayList<>();
            java.util.ArrayList<cl_device_id> gpuDevices = new java.util.ArrayList<>();
            for (cl_platform_id platform : platforms) {
                try {
                    int[] numDevicesArray = new int[1];
                    clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 0, null, numDevicesArray);
                    if (numDevicesArray[0] <= 0) {
                        continue;
                    }
                    cl_device_id[] devices = new cl_device_id[numDevicesArray[0]];
                    clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices.length, devices, null);
                    for (cl_device_id found : devices) {
                        gpuPlatforms.add(platform);
                        gpuDevices.add(found);
                    }
                } catch (CLException ignored) {
                }
            }
            if (gpuDevices.isEmpty()) {
                throw new UnsupportedOperationException("No GPU found");
            }
            int requested = Math.max(0, this.config.gpuIndex());
            int selected = requested;
            if (selected >= gpuDevices.size()) {
                selected = gpuDevices.size() - 1;
                logger.log(Level.WARNING, "gpuIndex {0} is out of range (0-{1}), using {2}",
                        requested, gpuDevices.size() - 1, selected);
            }
            for (int i = 0; i < gpuDevices.size(); i++) {
                logger.log(Level.INFO, "GPU {0}: {1}", i, deviceName(gpuDevices.get(i)));
            }
            cl_platform_id targetPlatform = gpuPlatforms.get(selected);
            cl_device_id targetDevice = gpuDevices.get(selected);
            logger.log(Level.INFO, "OpenCL Backend Initialized. Using GPU {0}: {1}", selected, deviceName(targetDevice));

            device = targetDevice;

            cl_context_properties props = new cl_context_properties();
            props.addProperty(CL_CONTEXT_PLATFORM, targetPlatform);
            context = clCreateContext(props, 1, new cl_device_id[]{targetDevice}, null, null, null);

            program = clCreateProgramWithSource(context, 1, new String[]{KERNEL_SOURCE}, null, null);
            clBuildProgram(program, 0, null, "-cl-mad-enable -cl-fast-relaxed-math", null, null);

            isInitialized = true;
        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize OpenCL GPU Backend", e);
        }
    }

    private static String deviceName(cl_device_id targetDevice) {
        long[] size = new long[1];
        clGetDeviceInfo(targetDevice, CL_DEVICE_NAME, 0, null, size);
        byte[] nameBuffer = new byte[(int) size[0]];
        clGetDeviceInfo(targetDevice, CL_DEVICE_NAME, nameBuffer.length, Pointer.to(nameBuffer), null);
        return new String(nameBuffer, 0, nameBuffer.length - 1).trim();
    }

    @Override
    public void setConfig(CollisionConfig config) {
        this.config = config;
    }

    @Override
    public void destroy() {
        if (!isInitialized) return;
        isInitialized = false;

        for (ThreadState state : ALL_THREAD_STATES) state.destroy();
        ALL_THREAD_STATES.clear();
        threadStateEpoch++;

        if (program != null) clReleaseProgram(program);
        if (context != null) clReleaseContext(context);
        program = null;
        context = null;
        device = null;
        maxSizeTouched.set(-1);
    }

    @Override
    public CollisionResult push(double[] locations, double[] aabb, int[] resultSizeOut) {
        if (!isInitialized) return null;

        int entityCount = aabb.length / 6;
        if (entityCount == 0) {
            resultSizeOut[0] = 0;
            return null;
        }

        int maxCollisions = entityCount * config.maxCollision();
        maxSizeTouched.updateAndGet(current -> Math.max(current, entityCount));

        ThreadState state = threadState();
        state.reallocBuffers(entityCount, maxCollisions);

        clEnqueueWriteBuffer(state.commandQueue, state.memAABB, CL_TRUE, 0,
                (long) entityCount * 6 * Sizeof.cl_double, Pointer.to(aabb), 0, null, null);

        clSetKernelArg(state.kComputeHash, 0, Sizeof.cl_mem, Pointer.to(state.memAABB));
        clSetKernelArg(state.kComputeHash, 1, Sizeof.cl_mem, Pointer.to(state.memHashes));
        clSetKernelArg(state.kComputeHash, 2, Sizeof.cl_mem, Pointer.to(state.memIndices));
        clSetKernelArg(state.kComputeHash, 3, Sizeof.cl_int, Pointer.to(new int[]{entityCount}));
        clSetKernelArg(state.kComputeHash, 4, Sizeof.cl_double, Pointer.to(new double[]{CELL_SIZE}));
        clEnqueueNDRangeKernel(state.commandQueue, state.kComputeHash, 1, null, new long[]{entityCount}, null, 0, null, null);

        clEnqueueReadBuffer(state.commandQueue, state.memHashes, CL_TRUE, 0, (long) entityCount * Sizeof.cl_uint, Pointer.to(state.cpuHashes), 0, null, null);
        clEnqueueReadBuffer(state.commandQueue, state.memIndices, CL_TRUE, 0, (long) entityCount * Sizeof.cl_uint, Pointer.to(state.cpuIndices), 0, null, null);

        radixSort32(state.cpuHashes, state.cpuIndices, state.tempKeys, state.tempValues, entityCount);

        clEnqueueWriteBuffer(state.commandQueue, state.memHashes, CL_TRUE, 0, (long) entityCount * Sizeof.cl_uint, Pointer.to(state.cpuHashes), 0, null, null);
        clEnqueueWriteBuffer(state.commandQueue, state.memIndices, CL_TRUE, 0, (long) entityCount * Sizeof.cl_uint, Pointer.to(state.cpuIndices), 0, null, null);

        clSetKernelArg(state.kResetGrid, 0, Sizeof.cl_mem, Pointer.to(state.memCellStarts));
        clSetKernelArg(state.kResetGrid, 1, Sizeof.cl_mem, Pointer.to(state.memCellEnds));
        clSetKernelArg(state.kResetGrid, 2, Sizeof.cl_int, Pointer.to(new int[]{TABLE_SIZE}));
        clEnqueueNDRangeKernel(state.commandQueue, state.kResetGrid, 1, null, new long[]{TABLE_SIZE}, null, 0, null, null);

        clSetKernelArg(state.kBuildGrid, 0, Sizeof.cl_mem, Pointer.to(state.memHashes));
        clSetKernelArg(state.kBuildGrid, 1, Sizeof.cl_mem, Pointer.to(state.memCellStarts));
        clSetKernelArg(state.kBuildGrid, 2, Sizeof.cl_mem, Pointer.to(state.memCellEnds));
        clSetKernelArg(state.kBuildGrid, 3, Sizeof.cl_int, Pointer.to(new int[]{entityCount}));
        clEnqueueNDRangeKernel(state.commandQueue, state.kBuildGrid, 1, null, new long[]{entityCount}, null, 0, null, null);

        int[] zeroCount = new int[]{0};
        clEnqueueWriteBuffer(state.commandQueue, state.memCounter, CL_TRUE, 0, Sizeof.cl_int, Pointer.to(zeroCount), 0, null, null);

        clSetKernelArg(state.kDetect, 0, Sizeof.cl_mem, Pointer.to(state.memAABB));
        clSetKernelArg(state.kDetect, 1, Sizeof.cl_mem, Pointer.to(state.memHashes));
        clSetKernelArg(state.kDetect, 2, Sizeof.cl_mem, Pointer.to(state.memIndices));
        clSetKernelArg(state.kDetect, 3, Sizeof.cl_mem, Pointer.to(state.memCellStarts));
        clSetKernelArg(state.kDetect, 4, Sizeof.cl_mem, Pointer.to(state.memCellEnds));
        clSetKernelArg(state.kDetect, 5, Sizeof.cl_mem, Pointer.to(state.memOutA));
        clSetKernelArg(state.kDetect, 6, Sizeof.cl_mem, Pointer.to(state.memOutB));
        clSetKernelArg(state.kDetect, 7, Sizeof.cl_mem, Pointer.to(state.memDensity));
        clSetKernelArg(state.kDetect, 8, Sizeof.cl_mem, Pointer.to(state.memCounter));
        clSetKernelArg(state.kDetect, 9, Sizeof.cl_int, Pointer.to(new int[]{entityCount}));
        clSetKernelArg(state.kDetect, 10, Sizeof.cl_int, Pointer.to(new int[]{maxCollisions}));
        clSetKernelArg(state.kDetect, 11, Sizeof.cl_double, Pointer.to(new double[]{CELL_SIZE}));
        clEnqueueNDRangeKernel(state.commandQueue, state.kDetect, 1, null, new long[]{entityCount}, null, 0, null, null);

        int[] outCountArr = new int[1];
        clEnqueueReadBuffer(state.commandQueue, state.memCounter, CL_TRUE, 0, Sizeof.cl_int, Pointer.to(outCountArr), 0, null, null);
        int collisionTimes = Math.min(outCountArr[0], maxCollisions);
        resultSizeOut[0] = collisionTimes;

        if (collisionTimes > 0) {
            clEnqueueReadBuffer(state.commandQueue, state.memOutA, CL_TRUE, 0, (long) collisionTimes * Sizeof.cl_int, Pointer.to(state.resultWrapper.arrA), 0, null, null);
            clEnqueueReadBuffer(state.commandQueue, state.memOutB, CL_TRUE, 0, (long) collisionTimes * Sizeof.cl_int, Pointer.to(state.resultWrapper.arrB), 0, null, null);
        }
        clEnqueueReadBuffer(state.commandQueue, state.memDensity, CL_TRUE, 0, (long) entityCount * Sizeof.cl_float, Pointer.to(state.resultWrapper.arrDensity), 0, null, null);

        return state.resultWrapper;
    }

    private void radixSort32(int[] keys, int[] vals, int[] keysBuf, int[] valsBuf, int n) {
        int[] histogram = new int[256];
        int[] srcKeys = keys;
        int[] srcVals = vals;
        int[] dstKeys = keysBuf;
        int[] dstVals = valsBuf;
        for (int pass = 0; pass < 4; pass++) {
            int shift = pass * 8;
            java.util.Arrays.fill(histogram, 0);
            for (int i = 0; i < n; i++) histogram[(srcKeys[i] >>> shift) & 0xFF]++;
            int offset = 0;
            for (int i = 0; i < 256; i++) {
                int count = histogram[i];
                histogram[i] = offset;
                offset += count;
            }
            for (int i = 0; i < n; i++) {
                int pos = (srcKeys[i] >>> shift) & 0xFF;
                int destIdx = histogram[pos]++;
                dstKeys[destIdx] = srcKeys[i];
                dstVals[destIdx] = srcVals[i];
            }
            int[] tempKeys = srcKeys;
            srcKeys = dstKeys;
            dstKeys = tempKeys;
            int[] tempVals = srcVals;
            srcVals = dstVals;
            dstVals = tempVals;
        }
    }
}
