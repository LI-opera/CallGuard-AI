using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Automation;

namespace CallGuard.PhoneLinkUi
{
    internal static class PhoneLinkEventProbe
    {
        private static readonly object Gate = new object();
        private static StreamWriter Writer;
        private static int EventCount;
        private static int TargetPid;

        [STAThread]
        public static int Main(string[] args)
        {
            try
            {
                string output = null;
                int seconds = 5;
                for (int i = 0; i < args.Length - 1; i++)
                {
                    if (args[i] == "--output") output = args[i + 1];
                    if (args[i] == "--seconds") seconds = int.Parse(args[i + 1], CultureInfo.InvariantCulture);
                }
                if (string.IsNullOrWhiteSpace(output) || seconds < 1 || seconds > 300)
                    throw new ArgumentException("Usage: PhoneLinkEventProbe.exe --output <new.jsonl> --seconds <1..300>");
                output = Path.GetFullPath(output);
                if (File.Exists(output)) throw new IOException("Refusing to overwrite existing evidence: " + output);
                Directory.CreateDirectory(Path.GetDirectoryName(output));

                Process[] processes = Process.GetProcessesByName("PhoneExperienceHost");
                if (processes.Length != 1) throw new InvalidOperationException("Expected one PhoneExperienceHost process.");
                TargetPid = processes[0].Id;
                AutomationElement desktop = AutomationElement.RootElement;
                AutomationElementCollection roots = desktop.FindAll(
                    TreeScope.Children,
                    new PropertyCondition(AutomationElement.ProcessIdProperty, TargetPid));
                if (roots.Count == 0) throw new InvalidOperationException("No PhoneExperienceHost top-level UI Automation window.");

                using (Writer = new StreamWriter(output, false, new UTF8Encoding(false)))
                {
                    Write("started", desktop, null);
                    AutomationEventHandler opened = OnOpened;
                    StructureChangedEventHandler structure = OnStructureChanged;
                    AutomationPropertyChangedEventHandler property = OnPropertyChanged;
                    try
                    {
                        Automation.AddAutomationEventHandler(
                            WindowPattern.WindowOpenedEvent, desktop, TreeScope.Children, opened);
                        foreach (AutomationElement root in roots)
                        {
                            Automation.AddStructureChangedEventHandler(root, TreeScope.Subtree, structure);
                            Automation.AddAutomationPropertyChangedEventHandler(
                                root, TreeScope.Subtree, property,
                                AutomationElement.NameProperty,
                                AutomationElement.IsEnabledProperty,
                                AutomationElement.IsOffscreenProperty);
                        }
                        Thread.Sleep(TimeSpan.FromSeconds(seconds));
                    }
                    finally
                    {
                        Automation.RemoveAutomationEventHandler(WindowPattern.WindowOpenedEvent, desktop, opened);
                        foreach (AutomationElement root in roots)
                        {
                            Automation.RemoveStructureChangedEventHandler(root, structure);
                            Automation.RemoveAutomationPropertyChangedEventHandler(root, property);
                        }
                    }
                    Write("stopped", desktop, null);
                }
                Console.WriteLine("events=" + EventCount + " output=" + output);
                return 0;
            }
            catch (Exception error)
            {
                Console.Error.WriteLine(error.GetType().Name + ": " + error.Message);
                return 1;
            }
        }

        private static void OnOpened(object sender, AutomationEventArgs args)
        {
            Write("window_opened", sender as AutomationElement, null);
        }

        private static void OnStructureChanged(object sender, StructureChangedEventArgs args)
        {
            Write("structure_changed", sender as AutomationElement, args.StructureChangeType.ToString());
        }

        private static void OnPropertyChanged(object sender, AutomationPropertyChangedEventArgs args)
        {
            string property = args.Property == AutomationElement.NameProperty ? "name" :
                args.Property == AutomationElement.IsEnabledProperty ? "enabled" :
                args.Property == AutomationElement.IsOffscreenProperty ? "offscreen" : "other";
            Write("property_changed", sender as AutomationElement, property);
        }

        private static void Write(string kind, AutomationElement element, string detail)
        {
            int pid = 0;
            string type = "Unknown";
            bool hasName = false;
            try
            {
                if (element != null)
                {
                    pid = element.Current.ProcessId;
                    type = element.Current.ControlType.ProgrammaticName.Replace("ControlType.", "");
                    hasName = !string.IsNullOrWhiteSpace(element.Current.Name);
                }
            }
            catch (ElementNotAvailableException) {}
            string processName = "Unknown";
            if (pid > 0)
            {
                try { processName = Process.GetProcessById(pid).ProcessName; }
                catch (ArgumentException) {}
            }
            Dictionary<string, object> record = new Dictionary<string, object>
            {
                { "schema", "callguard.phone_link_uia_event.v1" },
                { "captured_at_utc", DateTime.UtcNow.ToString("o", CultureInfo.InvariantCulture) },
                { "event", kind },
                { "detail", detail },
                { "process_id", pid },
                { "process", processName },
                { "target_process_id", TargetPid },
                { "control_type", type },
                { "has_accessible_name", hasName }
            };
            lock (Gate)
            {
                Writer.WriteLine(new JavaScriptSerializer().Serialize(record));
                Writer.Flush();
                EventCount++;
            }
        }
    }
}
