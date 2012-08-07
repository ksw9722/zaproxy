/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
package org.zaproxy.zap.extension.websocket.ui;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Vector;

import javax.swing.ImageIcon;

import org.apache.commons.collections.map.LRUMap;
import org.apache.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.zaproxy.zap.extension.websocket.WebSocketMessage.Direction;
import org.zaproxy.zap.extension.websocket.WebSocketMessageDTO;
import org.zaproxy.zap.extension.websocket.db.TableWebSocket;
import org.zaproxy.zap.extension.websocket.db.WebSocketMessagePrimaryKey;
import org.zaproxy.zap.extension.websocket.utility.PagingTableModel;

/**
 * This model uses the {@link TableWebSocket} instance to load only needed
 * entries from database. Moreover it shows only those entries that are not
 * blacklisted by given {@link WebSocketMessagesViewFilter}.
 */
public class WebSocketMessagesViewModel extends PagingTableModel<WebSocketMessageDTO> {
	
	private static final long serialVersionUID = -5047686640383236512L;

	private static final Logger logger = Logger.getLogger(WebSocketMessagesViewModel.class);
	
	private static final int PAYLOAD_PREVIEW_LENGTH = 150;
	
	/**
	 * Number of columns in this table model
	 */
	private static final int COLUMN_COUNT = 6;
	
	/**
	 * Names of all columns.
	 */
	private final Vector<String> columnNames;

	/**
	 * Used to show only specific messages.
	 */
	private WebSocketMessagesViewFilter filter;

	/**
	 * Interface to database.
	 */
	protected TableWebSocket table;

	/**
	 * If null, all messages are shown.
	 */
	private Integer activeChannelId;

	/**
	 * Avoid having two much SQL queries by caching result and allow next query
	 * after new message has arrived.
	 */
	private Integer cachedRowCount;
	private Object cachedRowCountSemaphore = new Object();

	private LRUMap fullMessagesCache;
	
	private static final ImageIcon outgoingDirection;
	private static final ImageIcon incomingDirection;
	
	static {
		outgoingDirection = new ImageIcon(WebSocketMessagesViewModel.class.getResource("/resource/icon/105_gray.png"));
		incomingDirection = new ImageIcon(WebSocketMessagesViewModel.class.getResource("/resource/icon/106_gray.png"));
	}
	
	/**
	 * Ctor.
	 * 
	 * @param webSocketTable 
	 * @param webSocketFilter 
	 */
	public WebSocketMessagesViewModel(TableWebSocket webSocketTable, WebSocketMessagesViewFilter webSocketFilter) {
		this(webSocketTable);

		filter = webSocketFilter;
	}
	
	/**
	 * Useful Ctor for subclasses.
	 */
	protected WebSocketMessagesViewModel(TableWebSocket webSocketTable) {
		super();
		
		table = webSocketTable;
		columnNames = getColumnNames();
		fullMessagesCache = new LRUMap(10);
	}
	
	protected Vector<String> getColumnNames() {
		Vector<String> names = new Vector<String>(getColumnCount());
		ResourceBundle msgs = Constant.messages;
		names.add(msgs.getString("websocket.table.header.id"));
		names.add(msgs.getString("websocket.table.header.direction"));
		names.add(msgs.getString("websocket.table.header.timestamp"));
		names.add(msgs.getString("websocket.table.header.opcode"));
		names.add(msgs.getString("websocket.table.header.payload_length"));
		names.add(msgs.getString("websocket.table.header.payload"));
		return names;
	}
	
	public void setActiveChannel(Integer channelId) {
		activeChannelId = channelId;
		clear();
		fireTableDataChanged();
	}

	public Integer getActiveChannelId() {
		return activeChannelId;
	}

	/**
	 * Returns the size of currently visible messages.
	 * 
	 * @return
	 */
	@Override
	public int getRowCount() {
		try {
			synchronized (cachedRowCountSemaphore) {
				if (cachedRowCount == null) {
					cachedRowCount = table.getMessageCount(getCriterionMessage(), getCriterionOpcodes());
				}
				return cachedRowCount;
			}
		} catch (SQLException e) {
			logger.error(e.getMessage(), e);
			return 0;
		}
	}

	protected WebSocketMessageDTO getCriterionMessage() {
		WebSocketMessageDTO message = new WebSocketMessageDTO();
		
		if (activeChannelId != null) {
			message.channel.id = activeChannelId;
		}
		
		if (filter.getDirection() != null) {
			message.isOutgoing = filter.getDirection().equals(Direction.OUTGOING) ? true : false;
		}
		
		return message;
	}

	protected List<Integer> getCriterionOpcodes() {
		return filter.getOpcodes();
	}
	
	@Override
	public Object getWebSocketValueAt(WebSocketMessageDTO message, int columnIndex) {
		switch (columnIndex) {
		case 0:
			return new WebSocketMessagePrimaryKey(message.channel.id, message.id);

		case 1:
			// had problems with ASCII arrows => use icons
			if (message.isOutgoing) {
				return outgoingDirection; //"→";
			} else {
				return incomingDirection; //"←";
			}

		case 2:
			return message.dateTime;

		case 3:
			return message.opcode + "=" + message.readableOpcode;

		case 4:
			return message.payloadLength;

		case 5:
			if (message.payload instanceof String) {
				if (((String) message.payload).length() > PAYLOAD_PREVIEW_LENGTH) {
					return ((String) message.payload).substring(0, PAYLOAD_PREVIEW_LENGTH - 1) + "...";
				} else {
					return (String) message.payload;
				}
			} else if (message.payload instanceof byte[]) {
				return "<binary data>";
			}
		}

		return null;
	}

	@Override
	protected Object getPlaceholderValueAt(int columnIndex) {
		if (getColumnClass(columnIndex).equals(String.class)) {
			return "..";
		}
		return null;
	}

	@Override
	protected List<WebSocketMessageDTO> loadPage(int offset, int length) {
		try {
			return table.getMessages(getCriterionMessage(), getCriterionOpcodes(), offset, length, PAYLOAD_PREVIEW_LENGTH);
		} catch (SQLException e) {
			logger.error(e.getMessage(), e);
			return new ArrayList<WebSocketMessageDTO>();
		}
	}

	/**
	 * Returns the number of columns.
	 * 
	 * @return
	 */
	@Override
	public int getColumnCount() {
		return COLUMN_COUNT;
	}

	/**
	 * Returns the name of the given column index.
	 * 
	 * @return
	 */
	@Override
	public String getColumnName(int columnIndex) {
		return columnNames.get(columnIndex);
	}
	
	/**
	 * Cells are not editable.
	 * 
	 * @return
	 */
	@Override
	public boolean isCellEditable(int row, int col) {
		return false;
	}

	/**
	 * Returns the type of column for given column index.
	 * 
	 * @return
	 */
	@Override
	public Class<? extends Object> getColumnClass(int columnIndex) {
		switch (columnIndex) {
		case 0:
			return WebSocketMessagePrimaryKey.class;
		case 1:
			return ImageIcon.class;
		case 2:
			return String.class;
		case 3:
			return String.class;
		case 4:
			return Integer.class;
		case 5:
			return String.class;
		}
		return null;
	}

	/**
	 * Might return null. Always check!
	 * <p>
	 * Retrieves {@link WebSocketMessageDTO} from database with full payload.
	 * </p>
	 * 
	 * @param rowIndex
	 * @return
	 */
	public WebSocketMessageDTO getDTO(int rowIndex) {
		WebSocketMessageDTO message = getRowObject(rowIndex);
		
		if (message == null) {
			return null;
		}
		
		String pk = message.toString();
		if (fullMessagesCache.containsKey(pk)) {
			return (WebSocketMessageDTO) fullMessagesCache.get(pk);
		} else {
			try {
				WebSocketMessageDTO fullMessage = table.getMessage(message.id, message.channel.id);
				fullMessagesCache.put(pk, fullMessage);
				
				return fullMessage;
			} catch (SQLException e) {
				logger.error("Error retrieving full message!",e);
				return message;
			}
		}
	}

	/**
	 * Call this method when a new filter is applied on the messages list.
	 */
	public void fireFilterChanged() {
		clear();
		fireTableDataChanged();
	}
	
	@Override
	protected void clear() {
		super.clear();
		
		synchronized (cachedRowCountSemaphore) {
			cachedRowCount = null;
		}
		
		fullMessagesCache.clear();
	}

	/**
	 * A new message has arrived. Find out if we need to
	 * {@link AbstractTableModel#fireTableDataChanged()}.
	 * 
	 * @param message
	 */
	public void fireMessageArrived(WebSocketMessageDTO message) {
		boolean isWhitelistedChannel = (activeChannelId == null) || message.channel.id.equals(activeChannelId);
		if ((filter != null && filter.isBlacklisted(message)) || !isWhitelistedChannel) {
			// no need to fire update, as it isn't active now
		} else {
			// find out where it is inserted and update precisely
			
			// if new row is inserted at the end
			// it suffices to fire inserted row at the end of list
			
			// with enabled row sorter, you'll have to take care about this
			int rowCount = getRowCount();
			
			synchronized (cachedRowCountSemaphore) {
				cachedRowCount = null;
			}
			
			fireTableRowsInserted(rowCount, rowCount);
		}
	}

	public Integer getModelRowIndexOf(WebSocketMessageDTO message) {
		if (message.id == null) {
			return null;
		}
		
		WebSocketMessageDTO criteria = getCriterionMessage();
		criteria.channel.id = message.channel.id;
		criteria.id = message.id;
		
		try {
			return table.getIndexOf(criteria, null);
		} catch (SQLException e) {
			logger.error(e.getMessage(), e);
			// maybe I'm right with this guess - try
			return message.id - 1;
		}
	}
}
