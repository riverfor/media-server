package com.kaltura.media.server.wowza.listeners;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.kaltura.client.KalturaApiException;
import com.kaltura.client.enums.KalturaDVRStatus;
import com.kaltura.client.enums.KalturaRecordStatus;
import com.kaltura.client.types.KalturaLiveStreamEntry;
import com.kaltura.media.server.ILiveStreamManager;
import com.kaltura.media.server.KalturaServer;
import com.kaltura.media.server.wowza.LiveStreamManager;
import com.wowza.wms.amf.AMFDataList;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.application.WMSProperties;
import com.wowza.wms.client.IClient;
import com.wowza.wms.dvr.DvrApplicationContext;
import com.wowza.wms.dvr.IDvrConstants;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.request.RequestFunction;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.IMediaStreamActionNotify;
import com.wowza.wms.stream.IMediaWriterActionNotify;
import com.wowza.wms.stream.livedvr.ILiveStreamDvrRecorderControl;
import com.wowza.wms.stream.livepacketizer.ILiveStreamPacketizerControl;

public class LiveStreamEntry extends ModuleBase {

	protected final static String REQUEST_PROPERTY_PARTNER_ID = "p";
	protected final static String REQUEST_PROPERTY_ENTRY_ID = "e";
	protected final static String REQUEST_PROPERTY_SERVER_INDEX = "i";
	protected final static String REQUEST_PROPERTY_TOKEN = "t";

	protected final static String CLIENT_PROPERTY_CONNECT_APP = "connectapp";
	protected final static String CLIENT_PROPERTY_PARTNER_ID = "partnerId";
	protected final static String CLIENT_PROPERTY_SERVER_INDEX = "serverIndex";
	protected final static String CLIENT_PROPERTY_ENTRY_ID = "entryId";
	
	protected final static int INVALID_SERVER_INDEX = -1;

	private LiveStreamManager liveStreamManager;
	
	private class DvrRecorderControl implements ILiveStreamDvrRecorderControl, ILiveStreamPacketizerControl {

		@Override
		public boolean shouldDvrRecord(String recorderName, IMediaStream stream) {
			return this.isThatStreamNeeded(stream);
		}
		
		private boolean isThatStreamNeeded(IMediaStream stream) {
			String streamName = stream.getName();
			
			String entryId = null;
			IClient client = stream.getClient();
			if(client != null){
				WMSProperties clientProperties = client.getProperties();
				if(!clientProperties.containsKey(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID)){
					getLogger().info("DvrRecorderControl.isThatStreamNeeded: stream [" + streamName + "] is not associated with entry");
					return false;
				}
				entryId = clientProperties.getPropertyStr(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID);
			}
			else{				
				Pattern pattern = Pattern.compile("^(\\d_[\\d\\w]{8})_\\d+$");
				Matcher matcher = pattern.matcher(streamName);
				if(!matcher.find()){
					getLogger().info("DvrRecorderControl.isThatStreamNeeded: stream [" + streamName + "] has no client");
					return false;
				}

				entryId = matcher.group(1);
			}

			KalturaLiveStreamEntry liveStreamEntry = liveStreamManager.get(entryId);

			if(liveStreamEntry == null){
				getLogger().debug("DvrRecorderControl.isThatStreamNeeded: [" + streamName + "] entry [" + entryId + "] not found");
				return false;
			}
			
			if(liveStreamEntry.dvrStatus != KalturaDVRStatus.ENABLED){
				getLogger().debug("DvrRecorderControl.isThatStreamNeeded: [" + streamName + "] DVR disabled");
				return false;
			}

			int dvrWindow = liveStreamManager.getDvrWindow(liveStreamEntry);
			getLogger().debug("DvrRecorderControl.isThatStreamNeeded: [" + streamName + "] DVR window [" + dvrWindow + "]");

			IApplicationInstance appInstance = stream.getStreams().getAppInstance();
			DvrApplicationContext ctx = appInstance.getDvrApplicationContext();
			ctx.setWindowDuration(dvrWindow);
			ctx.setArchiveStrategy(IDvrConstants.ARCHIVE_STRATEGY_DELETE);

			return true;
		}
		
		public boolean isLiveStreamPacketize(String packetizer, IMediaStream stream) {
			getLogger().debug("DvrRecorderControl.isLiveStreamPacketize [" + packetizer + ", " + stream.getName() + "]");
		
			if(packetizer.compareTo("dvrstreamingpacketizer") == 0){
				getLogger().debug("DvrRecorderControl.isLiveStreamPacketize check shouldDvrRecord");
				return this.isThatStreamNeeded(stream);
			}
			
			return true;
		}
	}
	
	class LiveStreamListener implements IMediaStreamActionNotify{
		
		public void onPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend){
			//IApplicationInstance appInstance = stream.getStreams().getAppInstance();

			IClient client = stream.getClient();
			if(client == null)
				return;
			
			WMSProperties clientProperties = client.getProperties();
			if(!clientProperties.containsKey(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID))
				return;
			
			KalturaLiveStreamEntry liveStreamEntry = liveStreamManager.get(clientProperties.getPropertyStr(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID));

			getLogger().debug("LiveStreamListener::onPublish: " + liveStreamEntry.id);

			if(liveStreamEntry.recordStatus == KalturaRecordStatus.ENABLED){
				liveStreamManager.startRecord(liveStreamEntry.id, stream, true, true, false);
			}
			
			liveStreamManager.onPublish(liveStreamEntry, clientProperties.getPropertyInt(LiveStreamEntry.CLIENT_PROPERTY_SERVER_INDEX, LiveStreamEntry.INVALID_SERVER_INDEX));
		}
	
		public void onUnPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend){
			IClient client = stream.getClient();
			if(client == null)
				return;
			
			WMSProperties clientProperties = client.getProperties();
			if(!clientProperties.containsKey(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID))
				return;
			
			KalturaLiveStreamEntry liveStreamEntry = liveStreamManager.get(clientProperties.getPropertyStr(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID));

			getLogger().debug("LiveStreamListener::onUnPublish: " + liveStreamEntry.id);
			liveStreamManager.onUnPublish(liveStreamEntry);
		}
	
		public void onPause(IMediaStream stream, boolean isPause, double location){}
	
		public void onPlay(IMediaStream stream, String streamName, double playStart, double playLen, int playReset){}
	
		public void onSeek(IMediaStream stream, double location){}
	
		public void onStop(IMediaStream stream){}
	}

	public void onStreamCreate(IMediaStream stream){
		stream.addClientListener(new LiveStreamListener());
	}

	public void onDisconnect(IClient client) {
		WMSProperties clientProperties = client.getProperties();
		if(clientProperties.containsKey(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID)){
			String entryId = clientProperties.getPropertyStr(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID);
			liveStreamManager.onDisconnect(entryId);
			getLogger().info("LiveStreamEntry::onDisconnect: Entry removed [" + entryId + "]");
		}
	}

	public void onConnect(IClient client, RequestFunction function, AMFDataList params) {
		WMSProperties clientProperties = client.getProperties();
		String entryPoint = clientProperties.getPropertyStr(LiveStreamEntry.CLIENT_PROPERTY_CONNECT_APP);
		getLogger().debug("LiveStreamEntry::onConnect: " + entryPoint);
		
		String[] requestParts = entryPoint.split("/");
		HashMap<String, String> requestParams = new HashMap<String, String>();
		String field = null;
		for (int i = 1; i < requestParts.length; ++i)
		{
		    if(field == null)
		    {
		    	field = requestParts[i];
		    }
		    else
		    {
		    	requestParams.put(field, requestParts[i]);
		    	getLogger().debug("LiveStreamEntry::onConnect: " + field + ": " + requestParams.get(field));
		    	field = null;
		    }
		}
		
		int partnerId = Integer.parseInt(requestParams.get(LiveStreamEntry.REQUEST_PROPERTY_PARTNER_ID));
		String entryId = requestParams.get(LiveStreamEntry.REQUEST_PROPERTY_ENTRY_ID);
		String token = requestParams.get(LiveStreamEntry.REQUEST_PROPERTY_TOKEN);

		clientProperties.setProperty(LiveStreamEntry.CLIENT_PROPERTY_PARTNER_ID, partnerId);
		clientProperties.setProperty(LiveStreamEntry.CLIENT_PROPERTY_SERVER_INDEX, Integer.parseInt(requestParams.get(LiveStreamEntry.REQUEST_PROPERTY_SERVER_INDEX)));

		KalturaLiveStreamEntry liveStreamEntry;
		try {
			liveStreamEntry = liveStreamManager.get(entryId, partnerId);
		} catch (KalturaApiException e) {
			getLogger().error("LiveStreamEntry::onConnect: unable to get entry [" + entryId + "]: " + e.getMessage());
			client.rejectConnection("Unable to get entry [" + entryId + "]", "Unable to get entry [" + entryId + "]");
			return;
		}
		clientProperties.setProperty(LiveStreamEntry.CLIENT_PROPERTY_ENTRY_ID, entryId);
		getLogger().info("LiveStreamEntry::onConnect: Entry added [" + entryId + "]");
		
		if(token.compareTo(liveStreamEntry.streamPassword) != 0){
			getLogger().error("LiveStreamEntry::onConnect: Invalid token [" + token + "] for entry [" + entryId + "] with token [" + liveStreamEntry.streamPassword + "]");
			client.rejectConnection("Invalid token", "Invalid token");
		}
	}

	public void onAppStart(IApplicationInstance appInstance) {
		DvrRecorderControl dvrRecorderControl = new DvrRecorderControl();
		appInstance.setLiveStreamDvrRecorderControl(dvrRecorderControl);
		appInstance.setLiveStreamPacketizerControl(dvrRecorderControl);

		ILiveStreamManager serverLiveStreamManager = (ILiveStreamManager) KalturaServer.getManager(ILiveStreamManager.class);
		
		if(serverLiveStreamManager == null || !(serverLiveStreamManager instanceof LiveStreamManager)){
			getLogger().error("LiveStreamEntry::onAppStart: Live stream manager not defined");
			return;
		}

		liveStreamManager = (LiveStreamManager) serverLiveStreamManager;
		appInstance.addMediaWriterListener((IMediaWriterActionNotify) liveStreamManager);
	}
}