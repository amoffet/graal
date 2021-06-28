/*
 * Copyright (c) 2017, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.hosted.code;

import java.lang.reflect.Executable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.oracle.svm.hosted.analysis.SvmStaticAnalysisEngine;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.hosted.Feature;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.c.BoxedRelocatedPointer;
import com.oracle.svm.core.code.IsolateLeaveStub;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.hosted.FeatureImpl.DuringSetupAccessImpl;
import com.oracle.svm.hosted.c.NativeLibraries;
import com.oracle.svm.hosted.meta.MethodPointer;

import jdk.vm.ci.meta.ResolvedJavaType;

public final class CEntryPointCallStubSupport {

    static void initialize(SvmStaticAnalysisEngine analysis) {
        ImageSingletons.add(CEntryPointCallStubSupport.class, new CEntryPointCallStubSupport(analysis));
    }

    public static CEntryPointCallStubSupport singleton() {
        return ImageSingletons.lookup(CEntryPointCallStubSupport.class);
    }

    private final SvmStaticAnalysisEngine analysis;
    private final Map<AnalysisMethod, AnalysisMethod> methodToStub = new ConcurrentHashMap<>();
    private final Map<AnalysisMethod, AnalysisMethod> methodToJavaStub = new ConcurrentHashMap<>();
    private NativeLibraries nativeLibraries;

    /**
     * Cache the BoxedRelocatedPointer objects to ensure that the same constant is seen during
     * analysis and compilation.
     */
    private final ConcurrentHashMap<CFunctionPointer, BoxedRelocatedPointer> cFunctionPointerCache = new ConcurrentHashMap<>();

    private CEntryPointCallStubSupport(SvmStaticAnalysisEngine analysis) {
        this.analysis = analysis;
    }

    public AnalysisMethod getStubForMethod(Executable reflectionMethod) {
        AnalysisMethod method = analysis.getMetaAccess().lookupJavaMethod(reflectionMethod);
        return getStubForMethod(method);
    }

    public AnalysisMethod registerStubForMethod(Executable reflectionMethod, Supplier<CEntryPointData> entryPointDataSupplier) {
        AnalysisMethod method = analysis.getMetaAccess().lookupJavaMethod(reflectionMethod);
        return registerStubForMethod(method, entryPointDataSupplier);
    }

    public AnalysisMethod getStubForMethod(AnalysisMethod method) {
        return methodToStub.get(method);
    }

    public AnalysisMethod getMethodForStub(CEntryPointCallStubMethod method) {
        return method.lookupTargetMethod(analysis.getMetaAccess());
    }

    public AnalysisMethod registerStubForMethod(AnalysisMethod method, Supplier<CEntryPointData> entryPointDataSupplier) {
        return methodToStub.compute(method, (key, existingValue) -> {
            AnalysisMethod value = existingValue;
            if (value == null) {
                assert !analysis.getUniverse().sealed();
                CEntryPointData entryPointData = entryPointDataSupplier.get();
                CEntryPointCallStubMethod stub = CEntryPointCallStubMethod.create(method, entryPointData, analysis.getMetaAccess());
                AnalysisMethod wrapped = analysis.getUniverse().lookup(stub);
                analysis.addRootMethod(wrapped).registerAsEntryPoint(entryPointData);
                value = wrapped;
            }
            return value;
        });
    }

    public AnalysisMethod registerJavaStubForMethod(AnalysisMethod method) {
        return methodToJavaStub.compute(method, (key, existingValue) -> {
            AnalysisMethod value = existingValue;
            if (value == null) {
                assert !analysis.getUniverse().sealed();
                AnalysisMethod nativeStub = registerStubForMethod(method, () -> CEntryPointData.create(method));
                CFunctionPointer nativeStubAddress = MethodPointer.factory(nativeStub);
                String stubName = SubstrateUtil.uniqueShortName(method);
                ResolvedJavaType holderClass = analysis.getMetaAccess().lookupJavaType(IsolateLeaveStub.class).getWrapped();
                CEntryPointJavaCallStubMethod stub = new CEntryPointJavaCallStubMethod(method.getWrapped(), stubName, holderClass, nativeStubAddress);
                value = analysis.getUniverse().lookup(stub);
            }
            return value;
        });
    }

    void setNativeLibraries(NativeLibraries nativeLibraries) {
        this.nativeLibraries = nativeLibraries;
    }

    public NativeLibraries getNativeLibraries() {
        assert nativeLibraries != null;
        return nativeLibraries;
    }

    public BoxedRelocatedPointer getBoxedRelocatedPointer(CFunctionPointer cFunctionPointer) {
        return cFunctionPointerCache.computeIfAbsent(cFunctionPointer, t -> new BoxedRelocatedPointer(t));
    }
}

@AutomaticFeature
class CEntryPointCallStubFeature implements Feature {
    @Override
    public void duringSetup(DuringSetupAccess arg) {
        DuringSetupAccessImpl access = (DuringSetupAccessImpl) arg;
        CEntryPointCallStubSupport.initialize(access.getStaticAnalysisEngine());
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess arg) {
        BeforeAnalysisAccessImpl access = (BeforeAnalysisAccessImpl) arg;
        CEntryPointCallStubSupport.singleton().setNativeLibraries(access.getNativeLibraries());
    }
}
