/*
 * Copyright 2020-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.vlanbasedsr;

import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;

public class DeviceConf extends Config<ApplicationId> {

    public static final String DeviceId = "DeviceId";
    public static final String SubnetId = "SubnetId";
    public static final String SubnetMask = "SubnetMask";
    public static final String SegmentId = "SegmentId";

    @Override
    public boolean isValid(){
        return hasOnlyFields(DeviceId, SubnetId, SubnetMask, SegmentId);
    }

    public String deviceId(){
        return get(DeviceId, null);
    }

    public String subnetId(){
        return get(SubnetId, null);
    }

    public String subnetMask(){
        return get(SubnetMask, null);
    }

    public String segmentId(){
         return get(SegmentId, null);
    }

}
