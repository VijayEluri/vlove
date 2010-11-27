package vlove.virt;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.StreamConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import vlove.VirtException;
import vlove.dao.ConfigDao;
import vlove.model.NewVmWizardModel;

@Service
public class VirtBuilder {
	transient final Logger log = LoggerFactory.getLogger(getClass());
	
	@Autowired
	private ConfigDao cd;
	
	private List<String> createVirtMachineCommand(NewVmWizardModel newVm) {
		// TODO Validate that the model is configured properly
		
		List<String> command = new ArrayList<String>();
		command.add("sudo");
		command.add("vmbuilder");
		command.add("kvm");
		command.add("ubuntu");
		command.add("-v");
		command.add(String.format("--libvirt=%s", cd.getConfigItem("libvirt.url").getValue()));
		command.add(String.format("--hostname=%s", newVm.getVmName()));
		command.add(String.format("--rootsize=%d", newVm.getDiskSize()*1024));
		command.add(String.format("--swapsize=%d", newVm.getMemSize()));
		command.add(String.format("--suite=%s", newVm.getSuite()));
		command.add("--flavour=virtual");
		command.add(String.format("--arch=%s", newVm.getArch()));
		
		if (newVm.getNetworks().equalsIgnoreCase("manual")) {
			command.add(String.format("--bridge=%s", newVm.getBridge()));
		} else {
			command.add(String.format("--network=%s", newVm.getNetworks()));
		}
		
		command.add(String.format("--cpus=%d", newVm.getNumProcs()));
		command.add(String.format("--mem=%d", newVm.getMemSize()));
		
		log.debug("Command: {}", command);
		return command;
	}
	
	/**
	 * Returns the exit value of the process.
	 * 
	 * @param out
	 * @param err
	 * @return
	 * @throws VirtException
	 */
	public int createVirtualMachine(NewVmWizardModel newVm, StreamConsumer out, StreamConsumer err) throws VirtException {
		List<String> command = createVirtMachineCommand(newVm);
		if (command == null || command.size() < 1) {
			throw new VirtException("Command needs to be provided.");
		}
		
		try {
			Commandline cs = new Commandline();
			cs.setExecutable(command.get(0));
			if (command.size() > 1) {
				cs.addArguments(command.subList(1, command.size()).toArray(new String[]{}));
			}
			PipedOutputStream pOut = new PipedOutputStream();
			PipedInputStream pIs = new PipedInputStream(pOut);
			
			List<ConsumerListener> listeners = new ArrayList<ConsumerListener>();
			listeners.add(new ConsumerListener(pOut, Pattern.compile("\\[sudo\\] password for \\w+:"), cd.getConfigItem("sudoPassword").getValue()));
			
			NestedStreamConsumer nOut = new NestedStreamConsumer(listeners, pOut, out);
			return CommandLineUtils.executeCommandLine(cs, pIs, nOut, err);
		} catch (CommandLineException ce) {
			throw new VirtException("Could not execute command.", ce);
		} catch (IOException ie) {
			throw new VirtException("Could not execute command.", ie);
		}
	}
	
	private class NestedStreamConsumer implements StreamConsumer {
		private final List<ConsumerListener> listeners; 
		private final PipedOutputStream pOut;
		private final StreamConsumer upstream;
		
		public NestedStreamConsumer(List<ConsumerListener> listeners, PipedOutputStream pOut, StreamConsumer upstream) {
			this.listeners = listeners;
			this.pOut = pOut;
			this.upstream = upstream;
		}

		@Override
		public void consumeLine(String line) {
			for (ConsumerListener cl : listeners) {
				cl.consumeLine(line);
			}
			upstream.consumeLine(line);
		}
	}
	
	private class ConsumerListener {
		private final PipedOutputStream pOut;
		private final Pattern p;
		private final String action;
		
		public ConsumerListener(PipedOutputStream pOut, Pattern p, String action) {
			this.pOut = pOut;
			this.p = p;
			this.action = action;
		}
		
		public void consumeLine(String line) {
			Matcher m = p.matcher(line);
			if (m.matches()) {
				try {
					pOut.write(action.getBytes());
				} catch (IOException ie) {
					log.warn("Could not write to piped output stream.", ie);
				}
			}
		}
	}
}