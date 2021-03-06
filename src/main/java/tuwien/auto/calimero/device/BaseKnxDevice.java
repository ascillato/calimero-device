/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2011, 2018 B. Malinowsky

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library. Thus, the terms and
    conditions of the GNU General Public License cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under terms
    of your choice, provided that you also meet, for each linked independent
    module, the terms and conditions of the license of that module. An
    independent module is a module which is not derived from or based on
    this library. If you modify this library, you may extend this exception
    to your version of the library, but you are not obligated to do so. If
    you do not wish to do so, delete this exception statement from your
    version.
*/

package tuwien.auto.calimero.device;

import static tuwien.auto.calimero.device.ios.InterfaceObject.DEVICE_OBJECT;
import static tuwien.auto.calimero.device.ios.InterfaceObject.KNXNETIP_PARAMETER_OBJECT;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EventObject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.slf4j.Logger;

import tuwien.auto.calimero.DeviceDescriptor;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.Settings;
import tuwien.auto.calimero.device.ios.InterfaceObject;
import tuwien.auto.calimero.device.ios.InterfaceObjectServer;
import tuwien.auto.calimero.device.ios.KnxPropertyException;
import tuwien.auto.calimero.knxnetip.ConnectionBase;
import tuwien.auto.calimero.knxnetip.KNXnetIPRouting;
import tuwien.auto.calimero.link.AbstractLink;
import tuwien.auto.calimero.link.KNXLinkClosedException;
import tuwien.auto.calimero.link.KNXNetworkLink;
import tuwien.auto.calimero.link.medium.KNXMediumSettings;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.mgmt.Description;
import tuwien.auto.calimero.mgmt.PropertyAccess.PID;

/**
 * Implementation of a KNX device for common device tasks. This type can either be used directly, with the device logic
 * for process communication and/or management services supplied during construction. Or, extended by a subtype which
 * implements the corresponding service interfaces ({@link ProcessCommunicationService}, {@link ManagementService}).
 * <p>
 * Notes for working with KNX devices: a KNX device can change its individual address. Therefore, do not use the address
 * as identifier.
 *
 * @author B. Malinowsky
 * @see KnxDeviceServiceLogic
 * @see ProcessCommunicationService
 * @see ManagementService
 */
public class BaseKnxDevice implements KnxDevice
{
	// The object instance determines which instance of an object type is
	// queried for properties. Always defaults to 1.
	private static final int objectInstance = 1;

	// Values used for manufacturer data DIB
	// PID.MANUFACTURER_ID
	private static final int defMfrId = 0;
	// PID.MANUFACTURER_DATA
	// one element is 4 bytes, value length has to be multiple of that
	// defaults to 'bm2011  '
	private static final byte[] defMfrData = new byte[] { 'b', 'm', '2', '0', '1', '1', ' ', ' ' };

	// property id to distinguish hardware types which are using the same
	// device descriptor mask version
	private static final int pidHardwareType = 78; // PDT Generic 6 bytes

	// service event threading
	static final int INCOMING_EVENTS_THREADED = 1;
	static final int OUTGOING_EVENTS_THREADED = 2;
	int threadingPolicy;

	// process & mgmt communication service tasks are processed as follows:
	//  *) producer / consumer pattern
	//  *) in-order task processing per producer
	//  *) sequential task processing per producer
	private static final ThreadFactory factory = Executors.defaultThreadFactory();
	private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
			new LinkedBlockingQueue<Runnable>(), (r) -> {
				final Thread t = factory.newThread(r);
				t.setName("Calimero Device Task (" + t.getName() + ")");
				t.setDaemon(true); // on shutdown, we won't execute any remaining tasks
				return t;
			});

	private boolean taskSubmitted;
	// local queue if a task is currently submitted to our executor service
	private final List<Runnable> tasks = new ArrayList<>();

	private final String name;
	private final DeviceDescriptor dd; // kept as variable in case it's DD2
	private final InterfaceObjectServer ios;
	private final Logger logger;

	private final ProcessCommunicationService process;
	private final ManagementService mgmt;

	private ProcessServiceNotifier procNotifier;
	private ManagementServiceNotifier mgmtNotifier;
	private KNXNetworkLink link;

	// default TP1 address
	private IndividualAddress self = new IndividualAddress(new byte[] { 0x02, (byte) 0xff });

	private static final int deviceMemorySize = 50_000;
	private final byte[] memory = new byte[deviceMemorySize];

	BaseKnxDevice(final String name, final DeviceDescriptor dd, final ProcessCommunicationService process,
		final ManagementService mgmt) throws KnxPropertyException
	{
		threadingPolicy = OUTGOING_EVENTS_THREADED;
		this.name = name;
		this.dd = dd;
		ios = new InterfaceObjectServer(false);
		logger = LogService.getLogger("calimero.device." + name);

		this.process = process;
		this.mgmt = mgmt;

		initDeviceInfo();
	}

	/**
	 * Creates a new KNX device, requiring any subtype to initialize the service logic during construction.
	 * <p>
	 * The device address is either a configured subnetwork unique device address, or the default individual address if
	 * no address was assigned to the device yet. The default individual device address consists of a medium dependent
	 * default subnetwork address and the device address for unregistered devices. Unregistered devices are identified
	 * by using the device address 0xff, a value reserved for this purpose. The subnetwork address part describes the
	 * individual address' <i>area</i> and <i>line</i>. The default subnetwork address by medium is as follows, listed
	 * as <i>Medium</i>: <i>Subnetwork address</i>:
	 * <ul>
	 * <li>TP 1: 0x02</li>
	 * <li>PL 110: 0x04</li>
	 * <li>RF: 0x05</li>
	 * </ul>
	 *
	 * @param name KNX device name, used for human readable naming or device identification
	 * @param dd device descriptor
	 * @param device the device address, or the default individual address; if a device address is assigned, this
	 *        address shall be unique in the subnetwork the device resides
	 * @param link the KNX network link this device is attached to
	 * @throws KNXLinkClosedException if the network link is closed
	 * @throws KnxPropertyException on error setting KNX properties during device initialization
	 */
	BaseKnxDevice(final String name, final DeviceDescriptor dd, final IndividualAddress device,
		final KNXNetworkLink link) throws KNXLinkClosedException, KnxPropertyException
	{
		this(name, dd, (ProcessCommunicationService) null, null);
		setDeviceLink(link);
		setAddress(device);
	}

	/**
	 * Creates a new KNX device.
	 * <p>
	 * The device address is either a configured subnetwork unique device address, or the default
	 * individual address if no address was assigned to the device yet. The default individual
	 * device address consists of a medium dependent default subnetwork address and the device
	 * address for unregistered devices. Unregistered devices are identified by using the device
	 * address 0xff, a value reserved for this purpose. The subnetwork address part describes the
	 * individual address' <i>area</i> and <i>line</i>. The default subnetwork address by medium is
	 * as follows, listed as <i>Medium</i>: <i>Subnetwork address</i>:
	 * <ul>
	 * <li>TP 1: 0x02</li>
	 * <li>PL 110: 0x04</li>
	 * <li>RF: 0x05</li>
	 * </ul>
	 *
	 * @param name KNX device name, used for human readable naming or device identification
	 * @param dd device descriptor
	 * @param device the device address, or the default individual address; if a device address is
	 *        assigned, this address shall be unique in the subnetwork the device resides
	 * @param link the KNX network link this device is attached to
	 * @param process the device process communication service handler
	 * @param mgmt the device management service handler
	 * @throws KNXLinkClosedException if the network link is closed
	 * @throws KnxPropertyException on error setting KNX properties during device initialization
	 */
	public BaseKnxDevice(final String name, final DeviceDescriptor dd, final IndividualAddress device,
		final KNXNetworkLink link, final ProcessCommunicationService process,
		final ManagementService mgmt) throws KNXLinkClosedException, KnxPropertyException
	{
		this(name, dd, process, mgmt);
		setDeviceLink(link);
		setAddress(device);
	}

	/**
	 * Creates a new KNX device using a {@link KnxDeviceServiceLogic} argument, the device's communication link (and
	 * address) has to be subsequently assigned.
	 *
	 * @param name KNX device name, used for human readable naming or device identification
	 * @param logic KNX device service logic
	 * @throws KnxPropertyException on error initializing the device KNX properties
	 */
	public BaseKnxDevice(final String name, final KnxDeviceServiceLogic logic) throws KnxPropertyException
	{
		this(name, DeviceDescriptor.DD0.TYPE_5705, logic, logic);
		logic.setDevice(this);
	}

	/**
	 * Creates a new KNX device using a {@link KnxDeviceServiceLogic} and a network link argument.
	 * <p>
	 * The device address is supplied by the link's medium settings, and is only used if the address is not 0.0.0. An
	 * address should be a subnetwork unique device address or a default individual address (see
	 * {@link #BaseKnxDevice(String, DeviceDescriptor, IndividualAddress, KNXNetworkLink, ProcessCommunicationService, ManagementService)}).
	 *
	 * @param name KNX device name, used for human readable naming or device identification
	 * @param logic KNX device service logic
	 * @param link the KNX network link this device is attached to
	 * @throws KNXLinkClosedException on closed network link
	 * @throws KnxPropertyException on error initializing the device properties
	 */
	public BaseKnxDevice(final String name, final KnxDeviceServiceLogic logic, final KNXNetworkLink link)
		throws KNXLinkClosedException, KnxPropertyException
	{
		this(name, DeviceDescriptor.DD0.TYPE_5705, link.getKNXMedium().getDeviceAddress(), link, logic, logic);
		logic.setDevice(this);
	}

	/**
	 * Assigns a new KNX individual address to this device.
	 * <p>
	 * This method sets the new address, and does <i>not</i> perform any other management or
	 * configuration tasks, e.g., ensuring a subnetwork unique device address, or publish the new
	 * address on the network.
	 *
	 * @param address the new device address
	 */
	protected final synchronized void setAddress(final IndividualAddress address)
	{
		if (address == null)
			throw new NullPointerException("device address cannot be null");
		if (address.getRawAddress() == 0)
			return;
		self = address;

		final byte[] addr = self.toByteArray();
		setDeviceProperty(PID.SUBNET_ADDRESS, addr[0]);
		setDeviceProperty(PID.DEVICE_ADDRESS, addr[1]);

		try {
			setIpProperty(PID.KNX_INDIVIDUAL_ADDRESS, addr);
		}
		catch (final KnxPropertyException ignore) {
			// fails if we don't have a KNX IP object
		}
	}

	@Override
	public final synchronized IndividualAddress getAddress()
	{
		return self;
	}

	@Override
	public final synchronized void setDeviceLink(final KNXNetworkLink link) throws KNXLinkClosedException
	{
		this.link = link;
		// ??? necessary
		if (link == null)
			return;

		setDeviceProperty(PID.MAX_APDULENGTH, fromWord(link.getKNXMedium().maxApduLength()));
		final int medium = link.getKNXMedium().getMedium();
		ios.setProperty(InterfaceObject.CEMI_SERVER_OBJECT, objectInstance, PID.MEDIUM_TYPE, 1, 1, (byte) 0, (byte) medium);
		if (medium == KNXMediumSettings.MEDIUM_KNXIP)
			initKnxipProperties();

		final IndividualAddress address = link.getKNXMedium().getDeviceAddress();
		if (address.getDevice() != 0)
			setAddress(address);

		if (process instanceof KnxDeviceServiceLogic)
			((KnxDeviceServiceLogic) process).setDevice(this);
		resetNotifiers();
	}

	@Override
	public final synchronized KNXNetworkLink getDeviceLink()
	{
		return link;
	}

	@Override
	public final InterfaceObjectServer getInterfaceObjectServer()
	{
		return ios;
	}

	/**
	 * @return the task executor providing the threads to run the process communication and
	 *         management services
	 */
	public ExecutorService taskExecutor()
	{
		return executor;
	}

	@Override
	public String toString()
	{
		return name + " " + self;
	}

	void dispatch(final EventObject e, final Supplier<ServiceResult> dispatch,
		final BiConsumer<EventObject, ServiceResult> respond)
	{
		if (threadingPolicy == INCOMING_EVENTS_THREADED) {
			submitTask(() -> {
				try {
					Optional.ofNullable(dispatch.get()).ifPresent(sr -> respond.accept(e, sr));
				}
				finally {
					taskDone();
				}
			});
		}
		else {
			Optional.ofNullable(dispatch.get()).ifPresent(sr -> submitTask(() -> {
				try {
					respond.accept(e, sr);
				}
				finally {
					taskDone();
				}
			}));
		}
	}

	DeviceDescriptor deviceDescriptor()
	{
		return dd;
	}

	Logger logger()
	{
		return logger;
	}

	private synchronized void resetNotifiers() throws KNXLinkClosedException
	{
		if (procNotifier != null)
			procNotifier.close();
		procNotifier = link != null && process != null ? new ProcessServiceNotifier(this, process) : null;

		if (mgmtNotifier != null)
			mgmtNotifier.close();
		mgmtNotifier = link != null && mgmt != null ? new ManagementServiceNotifier(this, mgmt) : null;
	}

	private void initDeviceInfo() throws KnxPropertyException
	{
		// Device Object settings

		final byte[] desc = name.getBytes(Charset.forName("ISO-8859-1"));
		ios.setProperty(DEVICE_OBJECT, objectInstance, PID.DESCRIPTION, 1, desc.length, desc);

		final String[] sver = Settings.getLibraryVersion().split("\\.| |-", -1);
		int last = 0;
		try {
			last = sver.length > 2 ? Integer.parseInt(sver[2]) : 0;
		}
		catch (final NumberFormatException e) {}
		final int ver = Integer.parseInt(sver[0]) << 12 | Integer.parseInt(sver[1]) << 6 | last;
		setDeviceProperty(PID.VERSION, fromWord(ver));

		// Firmware Revision
		final int firmwareRev = 1;
		setDeviceProperty(PID.FIRMWARE_REVISION, (byte) firmwareRev);

		// Serial Number
		final byte[] sno = new byte[6];
		setDeviceProperty(PID.SERIAL_NUMBER, sno);

		// device status is not in programming mode
		setDeviceProperty(PID.PROGMODE, (byte) 0);
		// Programming Mode (memory address 0x60) set off
		setMemory(0x60, (byte) 0);

		setDeviceProperty(PID.MANUFACTURER_ID, fromWord(defMfrId));
		ios.setProperty(DEVICE_OBJECT, objectInstance, PID.MANUFACTURER_DATA, 1, defMfrData.length / 4, defMfrData);

		// Hardware Type
		final byte[] hwType = new byte[6];
		setDeviceProperty(pidHardwareType, hwType);

		// device descriptor
		if (dd instanceof DeviceDescriptor.DD0) {
			setDeviceProperty(PID.DEVICE_DESCRIPTOR, dd.toByteArray());

			// validity check on mask and hardware type octets (AN059v3, AN089v3)
			final DeviceDescriptor.DD0 dd0 = (DeviceDescriptor.DD0) dd;
			final int maskVersion = dd0.maskVersion();
			if ((maskVersion == 0x25 || maskVersion == 0x0705) && hwType[0] != 0) {
				logger.error("manufacturer-specific device identification of hardware type should be 0 for this mask!");
			}
		}

		// don't confuse this with PID_MAX_APDU_LENGTH of the Router Object (PID = 58!!)
		ios.setDescription(new Description(0, 0, PID.MAX_APDULENGTH, 0, 0, false, 0, 1, 3, 0), true);
		// can be between 15 and 254 bytes (255 is Escape code for extended L_Data frames)
		setDeviceProperty(PID.MAX_APDULENGTH, fromWord(15));

		// Order Info
		final byte[] orderInfo = new byte[10]; // PDT Generic 10 bytes
		setDeviceProperty(PID.ORDER_INFO, orderInfo);

		// PEI Types
		// in devices without PEI, value is 0
		// PEI type 1: Illegal adapter
		// PEI type 10, 12, 14 and 16: serial interface to application module
		// PEI type 10: protocol on top of FT1.2
		// PEI type 2, 4, 6, 8, 17: parallel I/O (17 = programmable I/O)
		final int peiType = 0; // unsigned char

		// Physical PEI
		setDeviceProperty(PID.PEI_TYPE, (byte) peiType);


		// cEMI server object setttings

		// set default medium to TP1 (Bit 1 set)
		ios.setProperty(InterfaceObject.CEMI_SERVER_OBJECT, objectInstance, PID.MEDIUM_TYPE, 1, 1, new byte[] { 0, 2 });


		// Application Program Object settings

		final int appProgamObject = InterfaceObject.APPLICATIONPROGRAM_OBJECT;
		ios.addInterfaceObject(appProgamObject);

		// Required PEI Type
		final int requiredPeiType = 0; // unsigned char
		ios.setProperty(appProgamObject, objectInstance, PID.PEI_TYPE, 1, 1, fromByte(requiredPeiType));

		final int[] runStateEnum = {
			0, // Halted or not loaded
			1, // Running
			2, // Ready for being executed
			3, // Terminated (app only starts again after restart/device reset)
			4, // Starting, required for apps with >2 s startup time
			5, // Shutting down
		};
		// TODO format is usage dependent: 1 byte read / 10 bytes write
		final int runState = runStateEnum[1];
		// Run State
		ios.setProperty(appProgamObject, objectInstance, PID.RUN_STATE_CONTROL, 1, 1, fromWord(runState));

		// Application ID
		final byte[] applicationVersion = new byte[5]; // PDT Generic 5 bytes
		ios.setProperty(appProgamObject, objectInstance, PID.PROGRAM_VERSION, 1, 1, applicationVersion);
	}

	private void setDeviceProperty(final int propertyId, final byte... data) throws KnxPropertyException
	{
		ios.setProperty(DEVICE_OBJECT, objectInstance, propertyId, 1, 1, data);
	}

	private void setIpProperty(final int propertyId, final byte... data)
	{
		ios.setProperty(KNXNETIP_PARAMETER_OBJECT, objectInstance, propertyId, 1, 1, data);
	}

	// [InetAddress, MulticastSocket]
	private Object[] ipInfo() throws ReflectiveOperationException {
		final KNXnetIPRouting conn = accessField(AbstractLink.class, "conn", link);
		final MulticastSocket socket = accessField(ConnectionBase.class, "socket", conn);
		return new Object[] { socket, conn.getRemoteAddress().getAddress() };
	}

	@SuppressWarnings("unchecked")
	private <T, U> T accessField(final Class<? extends U> clazz, final String field, final U obj)
		throws ReflectiveOperationException, SecurityException {
		Class<? extends U> cl = (Class<? extends U>) obj.getClass();
		while (!clazz.equals(cl))
			cl = (Class<? extends U>) cl.getSuperclass();
		final Field f = cl.getDeclaredField(field);
		f.setAccessible(true);
		return (T) f.get(obj);
	}

	// PID.KNXNETIP_DEVICE_CAPABILITIES
	// Bits LSB to MSB: 0 Device Management, 1 Tunneling, 2 Routing, 3 Remote Logging,
	// 4 Remote Configuration and Diagnosis, 5 Object Server
	private static final int defDeviceCaps = 4;

	private void initKnxipProperties() {
		boolean found = false;
		for (final InterfaceObject io : ios.getInterfaceObjects())
			found |= io.getType() == KNXNETIP_PARAMETER_OBJECT;
		if (!found)
			ios.addInterfaceObject(KNXNETIP_PARAMETER_OBJECT);

		setIpProperty(PID.PROJECT_INSTALLATION_ID, fromWord(0));
		setIpProperty(PID.KNX_INDIVIDUAL_ADDRESS, self.toByteArray());
		setIpProperty(PID.CURRENT_IP_ASSIGNMENT_METHOD, (byte) 1);
		setIpProperty(PID.IP_ASSIGNMENT_METHOD, (byte) 1);
		setIpProperty(PID.IP_CAPABILITIES, (byte) 0);

		// pull out IP info from KNX IP protocol
		byte[] ip = new byte[4];
		final byte[] mask = new byte[4];
		byte[] mac = new byte[6];
		int ttl = 0;
		byte[] mcast = new byte[4];
		try {
			final Object[] objects = ipInfo();
			final MulticastSocket socket = (MulticastSocket) objects[0];
			mcast = ((InetAddress) objects[1]).getAddress();

			final NetworkInterface netif = socket.getNetworkInterface();

			final List<InterfaceAddress> addresses = netif.getInterfaceAddresses();
			final Optional<InterfaceAddress> addr = addresses.stream().filter(a -> a.getAddress() instanceof Inet4Address).findFirst();
			if (addr.isPresent()) {
				ip = addr.get().getAddress().getAddress();

				final int prefixLength = addr.get().getNetworkPrefixLength();
				final long defMask = 0xffffffffL;
				final long intMask = defMask ^ (defMask >> prefixLength);
				ByteBuffer.wrap(mask).putInt((int) intMask);
			}
			else {
				ip = InetAddress.getLocalHost().getAddress();
			}

			mac = Optional.ofNullable(netif.getHardwareAddress()).orElse(mac);
			ttl = socket.getTimeToLive();
		}
		catch (ReflectiveOperationException | IOException | RuntimeException e) {
			logger.warn("initializing KNX IP properties, {}", e.toString());
		}

		setIpProperty(PID.CURRENT_IP_ADDRESS, ip);
		setIpProperty(PID.CURRENT_SUBNET_MASK, mask);

		final byte[] gw = new byte[4];
		setIpProperty(PID.CURRENT_DEFAULT_GATEWAY, gw);
		setIpProperty(PID.IP_ADDRESS, ip);
		setIpProperty(PID.SUBNET_MASK, mask);
		setIpProperty(PID.DEFAULT_GATEWAY, gw);
		setIpProperty(PID.MAC_ADDRESS, mac);

		try {
			final InetAddress defMcast = InetAddress.getByName(KNXnetIPRouting.DEFAULT_MULTICAST);
			setIpProperty(PID.SYSTEM_SETUP_MULTICAST_ADDRESS, defMcast.getAddress());
		}
		catch (final UnknownHostException ignore) {}


		setIpProperty(PID.ROUTING_MULTICAST_ADDRESS, mcast);
		setIpProperty(PID.TTL, (byte) ttl);

		final int deviceCaps = defDeviceCaps; // defDeviceCaps - 4;
		setIpProperty(PID.KNXNETIP_DEVICE_CAPABILITIES, fromWord(deviceCaps));
		setIpProperty(PID.KNXNETIP_DEVICE_STATE, (byte) 0);

		setIpProperty(PID.QUEUE_OVERFLOW_TO_IP, fromWord(0));
		// reset transmit counter to 0, 4 byte unsigned
		setIpProperty(PID.MSG_TRANSMIT_TO_IP, new byte[4]);

		// friendly name property entry is an array of 30 characters
		final byte[] data = Arrays.copyOf(name.getBytes(Charset.forName("ISO-8859-1")), 30);
		ios.setProperty(KNXNETIP_PARAMETER_OBJECT, objectInstance, PID.FRIENDLY_NAME, 1, data.length, data);

		// 100 ms is the default busy wait time
		setIpProperty(PID.ROUTING_BUSY_WAIT_TIME, fromWord(100));
	}

	private void submitTask(final Runnable task)
	{
		synchronized (tasks) {
			if (taskSubmitted)
				tasks.add(task);
			else {
				taskSubmitted = true;
				taskExecutor().submit(task);
			}
		}
	}

	private void taskDone()
	{
		synchronized (tasks) {
			if (tasks.isEmpty())
				taskSubmitted = false;
			else
				taskExecutor().submit(tasks.remove(0));
		}
	}

	synchronized byte[] deviceMemory() {
		return memory;
	}

	byte[] deviceMemory(final int startAddress, final int bytes) {
		return Arrays.copyOfRange(deviceMemory(), startAddress, startAddress + bytes);
	}

	private synchronized void setMemory(final int startAddress, final byte... data)
	{
		System.arraycopy(data, 0, memory, startAddress, data.length);
	}

	private static byte[] fromWord(final int word)
	{
		return new byte[] { (byte) (word >> 8), (byte) word };
	}

	private static byte[] fromByte(final int uchar)
	{
		return new byte[] { (byte) uchar };
	}
}
