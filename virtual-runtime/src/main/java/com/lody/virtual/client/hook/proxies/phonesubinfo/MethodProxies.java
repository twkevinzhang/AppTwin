package com.lody.virtual.client.hook.proxies.phonesubinfo;

import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.helper.utils.marks.FakeDeviceMark;

import java.lang.reflect.Method;

/**
 * @author Lody
 */
@SuppressWarnings("ALL")
class MethodProxies {

    @FakeDeviceMark("fake device id")
    static class GetDeviceId extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getDeviceId";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return getDeviceInfo().deviceId;
        }
    }

    static class GetDeviceIdForSubscriber extends GetDeviceId {

        @Override
        public String getMethodName() {
            return "getDeviceIdForSubscriber";
        }

    }

    /**
     * Subscriber identifiers are privileged on modern Android. Returning the per-environment
     * stable identifier avoids leaking the physical SIM and prevents a SecurityException from
     * terminating cloned processes that probe subscription identity.
     */
    @FakeDeviceMark("fake subscriber id")
    static class GetSubscriberId extends GetDeviceId {
        @Override
        public String getMethodName() {
            return "getSubscriberId";
        }
    }

    static class GetSubscriberIdForSubscriber extends GetSubscriberId {
        @Override
        public String getMethodName() {
            return "getSubscriberIdForSubscriber";
        }
    }

    @FakeDeviceMark("fake iccid")
    static class GetIccSerialNumber extends MethodProxy {

        @Override
        public String getMethodName() {
            return "getIccSerialNumber";
        }

        @Override
        public Object call(Object who, Method method, Object... args) throws Throwable {
            return getDeviceInfo().iccId;
        }
    }


    static class getIccSerialNumberForSubscriber extends GetIccSerialNumber {
        @Override
        public String getMethodName() {
            return "getIccSerialNumberForSubscriber";
        }
    }
}
