package com.virtualcam.onvif.onvif

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object OnvifXmlTemplates {

    fun getDeviceInformationResponse(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
  <soap:Body>
    <tds:GetDeviceInformationResponse>
      <tds:Manufacturer>VirtualCam Project</tds:Manufacturer>
      <tds:Model>Android Virtual Camera</tds:Model>
      <tds:FirmwareVersion>1.0.0</tds:FirmwareVersion>
      <tds:SerialNumber>VC-ANDROID-001</tds:SerialNumber>
      <tds:HardwareId>Android-VirtualDevice</tds:HardwareId>
    </tds:GetDeviceInformationResponse>
  </soap:Body>
</soap:Envelope>"""
    }

    fun getCapabilitiesResponse(localIp: String, port: Int): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
  <soap:Body>
    <tds:GetCapabilitiesResponse>
      <tds:Capabilities>
        <tt:Device>
          <tt:XAddr>http://$localIp:$port/onvif/device_service</tt:XAddr>
          <tt:Network>
            <tt:IPFilter>false</tt:IPFilter>
            <tt:ZeroConfiguration>false</tt:ZeroConfiguration>
            <tt:IPVersion6>false</tt:IPVersion6>
            <tt:DynDNS>false</tt:DynDNS>
          </tt:Network>
          <tt:System>
            <tt:DiscoveryResolve>false</tt:DiscoveryResolve>
            <tt:DiscoveryBye>false</tt:DiscoveryBye>
            <tt:RemoteDiscovery>false</tt:RemoteDiscovery>
            <tt:SystemBackup>false</tt:SystemBackup>
            <tt:SystemLogging>false</tt:SystemLogging>
            <tt:FirmwareUpgrade>false</tt:FirmwareUpgrade>
            <tt:SupportedVersions>
              <tt:Major>2</tt:Major>
              <tt:Minor>4</tt:Minor>
            </tt:SupportedVersions>
          </tt:System>
        </tt:Device>
        <tt:Media>
          <tt:XAddr>http://$localIp:$port/onvif/media_service</tt:XAddr>
          <tt:StreamingCapabilities>
            <tt:RTPMulticast>false</tt:RTPMulticast>
            <tt:RTP_TCP>true</tt:RTP_TCP>
            <tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP>
          </tt:StreamingCapabilities>
        </tt:Media>
      </tds:Capabilities>
    </tds:GetCapabilitiesResponse>
  </soap:Body>
</soap:Envelope>"""
    }

    fun getSystemDateAndTimeResponse(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val utcTime = sdf.format(Date())

        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
  <soap:Body>
    <tds:GetSystemDateAndTimeResponse>
      <tds:SystemDateAndTime>
        <tt:DateTimeType>NTP</tt:DateTimeType>
        <tt:DaylightSavings>false</tt:DaylightSavings>
        <tt:TimeZone>
          <tt:TZ>UTC</tt:TZ>
        </tt:TimeZone>
        <tt:UTCDateTime>
          <tt:Time>
            <tt:Hour>12</tt:Hour>
            <tt:Minute>0</tt:Minute>
            <tt:Second>0</tt:Second>
          </tt:Time>
          <tt:Date>
            <tt:Year>2026</tt:Year>
            <tt:Month>11</tt:Month>
            <tt:Day>8</tt:Day>
          </tt:Date>
        </tt:UTCDateTime>
      </tds:SystemDateAndTime>
    </tds:GetSystemDateAndTimeResponse>
  </soap:Body>
</soap:Envelope>"""
    }

    fun getScopesResponse(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
  <soap:Body>
    <tds:GetScopesResponse>
      <tds:Scopes>
        <tt:ScopeDef>Fixed</tt:ScopeDef>
        <tt:ScopeItem>onvif://www.onvif.org/type/video_encoder</tt:ScopeItem>
      </tds:Scopes>
      <tds:Scopes>
        <tt:ScopeDef>Fixed</tt:ScopeDef>
        <tt:ScopeItem>onvif://www.onvif.org/Profile/Streaming</tt:ScopeItem>
      </tds:Scopes>
      <tds:Scopes>
        <tt:ScopeDef>Fixed</tt:ScopeDef>
        <tt:ScopeItem>onvif://www.onvif.org/hardware/AndroidVirtualCam</tt:ScopeItem>
      </tds:Scopes>
      <tds:Scopes>
        <tt:ScopeDef>Fixed</tt:ScopeDef>
        <tt:ScopeItem>onvif://www.onvif.org/name/VirtualCam-ONVIF</tt:ScopeItem>
      </tds:Scopes>
    </tds:GetScopesResponse>
  </soap:Body>
</soap:Envelope>"""
    }

    fun getProfilesResponse(width: Int, height: Int, includeAudio: Boolean = false): String {
        val audioSourceXml = if (includeAudio) {
            """
        <tt:AudioSourceConfiguration token="AudioSourceConfig_1">
          <tt:Name>VirtualAudioSource</tt:Name>
          <tt:UseCount>1</tt:UseCount>
          <tt:SourceToken>AudioSource_1</tt:SourceToken>
        </tt:AudioSourceConfiguration>"""
        } else ""

        val audioEncoderXml = if (includeAudio) {
            """
        <tt:AudioEncoderConfiguration token="AudioEncoderConfig_1">
          <tt:Name>AAC_Config</tt:Name>
          <tt:UseCount>1</tt:UseCount>
          <tt:Encoding>AAC</tt:Encoding>
          <tt:Bitrate>64</tt:Bitrate>
          <tt:SampleRate>44100</tt:SampleRate>
          <tt:Multicast>
            <tt:Address>
              <tt:Type>IPv4</tt:Type>
              <tt:IPv4Address>0.0.0.0</tt:IPv4Address>
            </tt:Address>
            <tt:Port>0</tt:Port>
            <tt:TTL>5</tt:TTL>
            <tt:AutoStart>false</tt:AutoStart>
          </tt:Multicast>
          <tt:SessionTimeout>PT60S</tt:SessionTimeout>
        </tt:AudioEncoderConfiguration>"""
        } else ""

        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
  <soap:Body>
    <trt:GetProfilesResponse>
      <trt:Profiles token="Profile_Main" fixed="true">
        <tt:Name>MainStream</tt:Name>
        <tt:VideoSourceConfiguration token="VideoSourceConfig_1">
          <tt:Name>VirtualCameraSource</tt:Name>
          <tt:UseCount>1</tt:UseCount>
          <tt:SourceToken>VideoSource_1</tt:SourceToken>
          <tt:Bounds x="0" y="0" width="$width" height="$height"/>
        </tt:VideoSourceConfiguration>$audioSourceXml
        <tt:VideoEncoderConfiguration token="VideoEncoderConfig_1">
          <tt:Name>H264_Config</tt:Name>
          <tt:UseCount>1</tt:UseCount>
          <tt:Encoding>H264</tt:Encoding>
          <tt:Resolution>
            <tt:Width>$width</tt:Width>
            <tt:Height>$height</tt:Height>
          </tt:Resolution>
          <tt:Quality>5.0</tt:Quality>
          <tt:RateControl>
            <tt:FrameRateLimit>30</tt:FrameRateLimit>
            <tt:EncodingInterval>1</tt:EncodingInterval>
            <tt:BitrateLimit>2500</tt:BitrateLimit>
          </tt:RateControl>
          <tt:H264>
            <tt:GovLength>30</tt:GovLength>
            <tt:H264Profile>Main</tt:H264Profile>
          </tt:H264>
          <tt:Multicast>
            <tt:Address>
              <tt:Type>IPv4</tt:Type>
              <tt:IPv4Address>0.0.0.0</tt:IPv4Address>
            </tt:Address>
            <tt:Port>0</tt:Port>
            <tt:TTL>5</tt:TTL>
            <tt:AutoStart>false</tt:AutoStart>
          </tt:Multicast>
        </tt:VideoEncoderConfiguration>$audioEncoderXml
      </trt:Profiles>
    </trt:GetProfilesResponse>
  </soap:Body>
</soap:Envelope>"""
    }

    fun getStreamUriResponse(rtspUri: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
  <soap:Body>
    <trt:GetStreamUriResponse>
      <trt:MediaUri>
        <tt:Uri>$rtspUri</tt:Uri>
        <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
        <tt:InvalidAfterReboot>false</tt:InvalidAfterReboot>
        <tt:Timeout>PT60S</tt:Timeout>
      </trt:MediaUri>
    </trt:GetStreamUriResponse>
  </soap:Body>
</soap:Envelope>"""
    }

    fun getServicesResponse(localIp: String, port: Int): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
  <soap:Body>
    <tds:GetServicesResponse>
      <tds:Service>
        <tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace>
        <tds:XAddr>http://$localIp:$port/onvif/device_service</tds:XAddr>
        <tds:Version><tds:Major>2</tds:Major><tds:Minor>4</tds:Minor></tds:Version>
      </tds:Service>
      <tds:Service>
        <tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace>
        <tds:XAddr>http://$localIp:$port/onvif/media_service</tds:XAddr>
        <tds:Version><tds:Major>2</tds:Major><tds:Minor>4</tds:Minor></tds:Version>
      </tds:Service>
    </tds:GetServicesResponse>
  </soap:Body>
</soap:Envelope>"""
    }
}
