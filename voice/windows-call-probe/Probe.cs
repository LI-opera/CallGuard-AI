using System;
using System.IO;
using System.Text;
using System.Runtime.InteropServices;
using System.Management.Automation;
using System.Windows.Forms;

public static class Probe {
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode)]
    static extern int GetCurrentPackageFullName(ref uint length, StringBuilder name);
    public static string Identity() {
        uint size=0; int code=GetCurrentPackageFullName(ref size,null);
        if(code==15700) return "UNPACKAGED";
        if(code!=122) return "ERROR_"+code;
        var text=new StringBuilder((int)size);
        code=GetCurrentPackageFullName(ref size,text);
        return code==0?text.ToString():"ERROR_"+code;
    }
    static string Run(bool requestAccess, string output) {
        // Execute inside THIS process; a child powershell.exe would invalidate the identity test.
        using(var ps=PowerShell.Create()) {
            ps.AddScript(File.ReadAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"probe.ps1"),Encoding.UTF8));
            ps.AddParameter("OutputPath",output);
            ps.AddParameter("PackageIdentity",Identity());
            if(requestAccess) ps.AddParameter("RequestAccess",true);
            var values=ps.Invoke();
            if(ps.HadErrors) throw new InvalidOperationException("Probe execution failed. No call operation was requested.");
            var text=new StringBuilder();
            foreach(var value in values) text.AppendLine(value.ToString());
            return text.ToString();
        }
    }
    [STAThread]
    public static int Main(string[] args) {
        if(args.Length==2 && args[0]=="--read-only") {
            try {Run(false,Path.GetFullPath(args[1]));return 0;} catch {return 1;}
        }
        Application.EnableVisualStyles();
        var form=new Form {Text="CallGuard Windows Call Permission Probe",Width=900,Height=650};
        var status=new Label {Dock=DockStyle.Top,Height=70,Text="Identity: "+Identity()+"\r\nOnly requests device access; no registration, calls or audio capture."};
        var buttons=new FlowLayoutPanel {Dock=DockStyle.Top,Height=45};
        var read=new Button {Text="Read-only check",Width=170};
        var access=new Button {Text="Request system access",Width=210};
        var display=new TextBox {Dock=DockStyle.Fill,Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Both,WordWrap=false};
        string directory=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"CallGuard","WindowsCallProbe");
        EventHandler execute=(sender,e)=>{
            read.Enabled=access.Enabled=false;
            try {
                bool request=sender==access;
                if(request && Identity()=="UNPACKAGED") {display.Text="Install and launch the package before requesting access.";return;}
                Directory.CreateDirectory(directory);
                string output=Path.Combine(directory,request?"packaged-access.json":"packaged-discovery.json");
                display.Text=Run(request,output)+"\r\nSaved: "+output;
            } catch(Exception ex) {display.Text="Probe error: "+ex.GetType().Name;}
            finally {read.Enabled=access.Enabled=true;}
        };
        read.Click+=execute;access.Click+=execute;
        buttons.Controls.Add(read);buttons.Controls.Add(access);
        form.Controls.Add(display);form.Controls.Add(buttons);form.Controls.Add(status);
        form.Shown+=(sender,e)=> { if(args.Length==1 && args[0]=="--probe-access") access.PerformClick(); };
        Application.Run(form);return 0;
    }
}
